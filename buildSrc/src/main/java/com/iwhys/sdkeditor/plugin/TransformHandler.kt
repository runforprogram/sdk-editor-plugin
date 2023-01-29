package com.iwhys.sdkeditor.plugin

import com.android.SdkConstants
import com.android.build.api.transform.*
import com.android.ide.common.workers.ExecutorServiceAdapter
import com.android.ide.common.workers.WorkerExecutorFacade
import com.iwhys.sdkeditor.domain.ReplaceClass
import javassist.ClassPool
import javassist.CtClass
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ForkJoinPool
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


/**
 * Created on 2018/11/8 14:22
 * Description: transform操作处理器
 *
 * @author 王洪胜
 */
class TransformHandler(
    private val classPool: ClassPool,
    private val sdkEditorConfig: SdkEditorConfig,
    transformInvocation: TransformInvocation
) {

    private val isParallel = sdkEditorConfig.parallel

    private val outputProvider = transformInvocation.outputProvider

    private val dirInputs = mutableSetOf<DirectoryInput>()

    private val jarInputs = mutableMapOf<String, JarInput>()

    /**
     * 收集到的要修复的Jar文件名
     */
    private val targetJarNames = mutableSetOf<String>()

    /**
     * 收集到的Fix类信息
     */
    private val replaceClasses = mutableSetOf<String>()

    /**
     * 是否增量编译
     */
    private val isIncremental = transformInvocation.isIncremental

    init {
        log("isIncrementalMode = $isIncremental")
        if (!isIncremental) {
            outputProvider.deleteAll()
        }
        transformInvocation.inputs.forEach {
            dirInputs += it.directoryInputs
            for (jarInput in it.jarInputs) {
                classPool.addPathJarInput(jarInput)
                jarInputs[jarInput.file.absolutePath] = jarInput
            }
        }
    }

    /**
     * 执行信息的收集和sdk修复操作
     */
    fun handle() {
        gatherInfo()
        fixSdk()
        clear()
    }

    /**
     * 根据收集到的信息修复sdk中的bug类
     */
    private fun fixSdk() {
        if (isParallel) {
            fixSdkParallel()
        } else {
            fixSdkSerial()
        }
        log("All bug classes have been fixed.")
    }

    private fun fixSdkSerial() {
        log("Begin to fix the bug classes in serial.")
        for (jarInput in jarInputs.values) {
            val dest = outputProvider.jarOutput(jarInput)
            if (!isIncremental || (jarInput.status == Status.ADDED || jarInput.status == Status.CHANGED)) {
                val jarFile = jarInput.file
                val jarName = jarFile.name
                if (!isTargetJar(jarName)) {
                    log("Not the target jar package, output directly:${jarName}")
                    safe { FileUtils.copyFile(jarFile, dest) }
                } else {
                    log("Found the target jar package：${jarName}, prepare to fix.")
                    jarInput.removeClass { name !in replaceClasses }
                }
            } else if (isIncremental && jarInput.status == Status.REMOVED) {
                dest.delete()
            }
        }
    }

    private fun fixSdkParallel() {
        log("Begin to fix the bug classes in parallel.")
        val executor = ExecutorServiceAdapter(ForkJoinPool.commonPool())
        for (jarInput in jarInputs.values) {
            val dest = outputProvider.jarOutput(jarInput)
            if (!isIncremental || (jarInput.status == Status.ADDED || jarInput.status == Status.CHANGED)) {
                val jarFile = jarInput.file
                val jarName = jarFile.name
                if (!isTargetJar(jarName)) {
                    log("Not the target jar package, output directly:${jarName}")
                    executor.submit(object : WorkerExecutorFacade.WorkAction {
                        override fun run() {
                            safe { FileUtils.copyFile(jarFile, dest) }
                        }
                    })
                } else {
                    log("Found the target jar package：${jarName}, prepare to fix.")
                    executor.submit(object : WorkerExecutorFacade.WorkAction {
                        override fun run() {
                            jarInput.handleClass { name !in replaceClasses }
                        }
                    })
                }
            } else if (isIncremental && jarInput.status == Status.REMOVED) {
                dest.delete()
            }
        }
        safe {
            executor.await()
            executor.close()
        }
    }

    /**
     * 清理导入的类信息
     */
    private fun clear() {
        classPool.clear()
    }

    /**
     * 判断是否目标Jar包
     */
    private fun isTargetJar(jarName: String): Boolean {
        targetJarNames.forEach {
            if (jarName.contains(it)) {
                return true
            }
        }
        return false
    }

    /**
     * 收集要处理的信息
     * 默认只收集dirInputs中的信息，如果配置中标记了特定的jarInputs，则会同时遍历指定的jarInputs
     * 收集信息之后的dirInputs或者jarInputs会被直接输入，因为他们实际上应该都是开发者可控源文件的编译产物
     */
    private fun gatherInfo() {
        if (isParallel) {
            gatherInfoParallel()
        } else {
            gatherInfoSerial()
        }
        log("The classes information collection:$replaceClasses")
    }

    private fun gatherInfoSerial() {
        log("Begin to gather the classes information in serial.")
        dirInputs.forEach(infoFromDirInput)
        val jarInputNames = jarInputs.keys
        sdkEditorConfig.fixedJarNamesSet()?.mapNotNull {
            findInfoJarInput(it, jarInputNames)
        }?.forEach(infoFromJarInput)
    }

    private fun gatherInfoParallel() {
        val executor = ExecutorServiceAdapter(ForkJoinPool.commonPool())
        log("Begin to gather the classes information in parallel.")
        for (dirInput in dirInputs) {
            executor.submit(object : WorkerExecutorFacade.WorkAction {
                override fun run() {
                    infoFromDirInput(dirInput)
                }
            })
        }
        val jarInputNames = jarInputs.keys
        sdkEditorConfig.fixedJarNamesSet()?.mapNotNull {
            findInfoJarInput(it, jarInputNames)
        }?.forEach {
            executor.submit(object : WorkerExecutorFacade.WorkAction {
                override fun run() {
                    infoFromJarInput(it)
                }
            })
        }
        safe {
            executor.await()
            executor.close()
        }
    }

    /**
     * 是否需要用来收集信息的jar包
     */
    private fun findInfoJarInput(jarName: String, jarInputNames: Set<String>): JarInput? {
        jarInputNames.forEach {
            if (it.contains(jarName)) {
                return jarInputs.remove(it)
            }
        }
        return null
    }

    /**
     * 从目录文件中收集信息
     */
    private val infoFromDirInput = { dirInput: DirectoryInput ->
        classPool.addPathDirInput(dirInput)
        val dest = outputProvider.dirOutput(dirInput)
        val handleFile: (File) -> Unit = { file: File ->
            if (file.extension == SdkConstants.EXT_CLASS) {
                safe {
                    classPool.makeClass(file.inputStream())?.apply {
                        gatherInfo()
                        writeFile(dest.absolutePath)
                        detach()
                    }
                }
            } else {
                log("The file's extension is not class, output directly:${file.name}")
                file.copyToDir(dest)
            }
        }
        if (isIncremental) {
            dirInput.changedFiles.forEach { file, status ->
                val relativeFile = file.relativeTo(dirInput.file)
                val outputFile = dest.resolve(relativeFile)
                when (status) {
                    Status.ADDED, Status.CHANGED -> handleFile(file)
                    Status.REMOVED -> outputFile.delete()
                    else -> {
                    }
                }
            }
        } else {
            FileUtils.listFiles(dirInput.file, null, true).forEach(handleFile)
        }
    }

    /**
     * 从jar文件中收集信息
     */
    private val infoFromJarInput: (JarInput) -> Unit = { jarInput ->
        log("Gathering classes information from jar:${jarInput.name}")
        jarInput.handleClass {
            gatherInfo()
            true
        }
    }

    /**
     * 从jar包中移除被替换的class
     */
    private fun JarInput.removeClass(block: CtClass.() -> Boolean) {

//        val dest = outputProvider.jarOutput(this)
        log("remove replace class")
        val dest = if (this.name.endsWith("jar")){
            outputProvider.getContentLocation(
                this.name, this.contentTypes, this.scopes, Format.JAR)
        }else{
            outputProvider.getContentLocation(
                this.name+this.file.name, this.contentTypes, this.scopes, Format.JAR)
        }

        val from = ZipFile(this.file)
        val to   = ZipOutputStream(FileOutputStream(dest))
        from.entries().toList().forEach {
           classPool.makeClass(from.getInputStream(it))?.apply {
               val need = block()
               if (need) {
                   to.putNextEntry(ZipEntry(it.name))
                   to.write(from.getInputStream(it).readBytes())
               }
           }
        }
        to.closeEntry()
        to.close()
    }

    /**
     * 处理JarInput中的类
     * @param block 从JarInput中成功取出CtClass类时的回调，其返回值表示处理之后的CtClass文件是否需要输出
     */
    private fun JarInput.handleClass(block: CtClass.() -> Boolean) {
        val dest = outputProvider.jarOutput(this)
        val jarFileTmpDir = JarUtil.getJarFileTmpDir(dest)
        val jarFile = JarFile(this.file)
        jarFile.stream().forEach {
            if (it.name.endsWith(SdkConstants.DOT_CLASS)) {
                val inputStream = jarFile.getInputStream(it)
                safe {
                    classPool.makeClass(inputStream)?.apply {
                        val needOutput = block()
                        if (needOutput) {
                            writeFile(jarFileTmpDir)
                        } else {
                            log("Replaced the bug class:$name")
                        }
                        detach()
                    }
                }
            } else {
                val outFile = File(jarFileTmpDir, it.name)
                if (it.isDirectory && !outFile.exists()) {
                    log("The file is directory ${it.name} in the target jar package:${jarFile.name}")
                    outFile.mkdirs()
                } else {
                    log("The file not end with 'class' in the target jar package:${jarFile.name}")
                    val inputStream = jarFile.getInputStream(it)
                    FileUtils.write(outFile, inputStream.reader().readText())
                }
            }
        }
        val tmpDirFile = File(jarFileTmpDir)
        log("Repackage and output the target jar package:$dest")
        JarUtil.jarFile(tmpDirFile, dest)
        safe {
            FileUtils.deleteDirectory(tmpDirFile)
        }
    }

    /**
     * 从CtClass中收集必要的信息
     */
    private fun CtClass.gatherInfo() {
        if (hasAnnotation(ReplaceClass::class.java)) {
            val jarName = (getAnnotation(ReplaceClass::class.java) as ReplaceClass).value
            if (jarName.isEmpty()) {
                log("Note:the annotation in the Fix class is missing the value of the jar package name:$name")
            }
            handleReplaceClass(jarName)
        }
    }

    /**
     * 处理替换类信息
     */
    private fun CtClass.handleReplaceClass(jarName: String) {
        val finalJarName = jarName.replace(':', '-')
        // 递归处理内部类
        nestedClasses?.forEach {
            it.handleReplaceClass(finalJarName)
        }
        targetJarNames += jarName
        targetJarNames += finalJarName
        replaceClasses += name
        log("Found the Fix class named:$name the jar package name:$finalJarName")
    }

}