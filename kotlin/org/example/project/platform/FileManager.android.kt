package org.example.project.platform

import java.io.File

actual class FileManager {
    actual fun readTextFile(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }
    
    actual fun writeTextFile(path: String, content: String): Boolean {
        return try {
            val file = File(path)
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    actual fun fileExists(path: String): Boolean {
        return File(path).exists()
    }
    
    actual fun listFiles(directory: String, extension: String?): List<String> {
        return try {
            val dir = File(directory)
            if (dir.isDirectory) {
                dir.listFiles { file ->
                    file.isFile && (extension == null || file.extension.lowercase() == extension.lowercase())
                }?.map { it.name } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    actual fun isAbsolute(path: String): Boolean {
        return File(path).isAbsolute
    }
    
    actual fun getParentDirectory(path: String): String? {
        return File(path).parentFile?.absolutePath
    }
    
    actual fun getAbsolutePath(path: String): String {
        return File(path).absolutePath
    }
    
    actual fun combinePath(parent: String, child: String): String {
        return File(parent, child).path
    }
    
    actual fun createDirectory(path: String): Boolean {
        return try {
            File(path).mkdirs()
        } catch (e: Exception) {
            false
        }
    }
    
    actual fun moveFile(sourcePath: String, targetPath: String): Boolean {
        return try {
            val sourceFile = File(sourcePath)
            val targetFile = File(targetPath)
            sourceFile.renameTo(targetFile)
        } catch (e: Exception) {
            false
        }
    }
}

actual fun getFileManager(): FileManager = FileManager() 