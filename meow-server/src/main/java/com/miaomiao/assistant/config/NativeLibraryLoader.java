package com.miaomiao.assistant.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Paths;

/**
 * Native库加载配置
 * 用于加载Opus编码的native库
 * 支持通过配置文件指定native库所在文件夹: native.opus.library-dir
 */
@Slf4j
@Component
public class NativeLibraryLoader {

    private static final String LIBRARY_NAME = "opus-jni-native";

    /**
     * 配置文件指定的native库所在文件夹路径
     * 可以是绝对路径，如: D:/libs/native 或 /opt/native
     * 文件夹下应包含对应平台的库文件(opus-jni-native.dll 或 libopus-jni-native.so)
     * 留空则自动检测
     */
    @Value("${native.opus.library-dir:}")
    private String configuredLibraryDir;

    @Getter
    private boolean loaded = false;

    /**
     * 成功加载native库的目录路径
     * 供其他组件（如OpusEncoder）使用
     */
    @Getter
    private File nativeDirectory = null;

    @PostConstruct
    public void init() {
        loadNativeLibrary();
    }

    private void loadNativeLibrary() {
        String libraryFileName = getLibraryFileName();
        String platform = detectPlatform();
        
        if (platform == null) {
            log.error("不支持当前操作系统，仅支持64位Windows和Linux");
            return;
        }

        // 1. 优先使用配置文件指定的文件夹路径
        if (StringUtils.hasText(configuredLibraryDir)) {
            // 尝试直接在配置目录下查找
            if (loadFromDirectory(configuredLibraryDir, libraryFileName)) {
                return;
            }
            // 尝试配置目录下的平台子目录
            String platformSubDir = Paths.get(configuredLibraryDir, platform).toString();
            if (loadFromDirectory(platformSubDir, libraryFileName)) {
                return;
            }
            log.warn("配置的native库文件夹无效: {}", configuredLibraryDir);
        }

        // 2. 尝试从工作目录的native文件夹加载
        String[] possibleDirs = {
                // 当前工作目录/native/平台
                Paths.get("native", platform).toAbsolutePath().toString(),
                // 当前工作目录/native
                Paths.get("native").toAbsolutePath().toString(),
                // jar包所在目录/native/平台
                getJarDirectory() + "/native/" + platform,
                // jar包所在目录/native
                getJarDirectory() + "/native"
        };

        for (String dir : possibleDirs) {
            if (loadFromDirectory(dir, libraryFileName)) {
                return;
            }
        }

        // 3. 尝试从系统库路径加载
        try {
            System.loadLibrary(LIBRARY_NAME);
            loaded = true;
            log.info("从系统库路径加载native库成功: {}", LIBRARY_NAME);
            return;
        } catch (UnsatisfiedLinkError e) {
            log.debug("从系统库路径加载失败: {}", e.getMessage());
        }

        log.error("无法加载Opus native库！请在配置文件设置 native.opus.library-dir 指向包含native库的文件夹");
    }

    private boolean loadFromDirectory(String dirPath, String libraryFileName) {
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }
        
        File libraryFile = new File(dir, libraryFileName);
        if (libraryFile.exists() && libraryFile.isFile()) {
            try {
                System.load(libraryFile.getAbsolutePath());
                loaded = true;
                nativeDirectory = dir;
                log.info("成功加载Opus native库: {}", libraryFile.getAbsolutePath());
                return true;
            } catch (UnsatisfiedLinkError e) {
                log.warn("加载native库失败 [{}]: {}", libraryFile.getAbsolutePath(), e.getMessage());
            }
        }
        return false;
    }

    /**
     * 检测平台，仅支持64位Windows和Linux
     */
    private String detectPlatform() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        boolean is64Bit = osArch.contains("64") || osArch.contains("amd64");

        if (!is64Bit) {
            log.error("不支持32位操作系统");
            return null;
        }

        if (osName.contains("win")) {
            return "windows-x64";
        } else if (osName.contains("linux")) {
            return "linux-x64";
        }
        
        log.warn("不支持的操作系统: {} {}", osName, osArch);
        return null;
    }

    /**
     * 获取库文件名
     */
    private String getLibraryFileName() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return LIBRARY_NAME + ".dll";
        } else {
            return "lib" + LIBRARY_NAME + ".so";
        }
    }

    private String getJarDirectory() {
        try {
            String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            return new File(jarPath).getParent();
        } catch (Exception e) {
            return ".";
        }
    }
}
