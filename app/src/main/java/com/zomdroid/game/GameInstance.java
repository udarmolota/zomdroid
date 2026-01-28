package com.zomdroid.game;

import com.zomdroid.AppStorage;
import com.zomdroid.FileUtils;

import java.io.File;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.StringJoiner;

public class GameInstance {
    private static final String INSTANCES_ROOT_DIR_NAME = "instances";
    public static final String GAME_FILES_DIR_NAME = "game";

    private String name;
    private String buildVersion;
    private String homePath;
    private boolean installationFinished = false;
    private String[] classPath;
    private String[] extraClassPath;
    private String[] libraryPath;
    private String[] libraryPathForEmulation;
    private String fmodLibraryPath;
    private String[] extraJvmArgs;
    private String[] args;
    private String mainClassName;
    private String javaAgentPath;
    private String javaAgentArgs;

    public GameInstance(String name, InstallationPreset preset) throws FileSystemException {
        this.name = name;
        this.buildVersion = preset.buildVersion;
        makeDirs();
        this.classPath = preset.classPathArray;
        this.extraClassPath = preset.extraJars;
        this.libraryPath = preset.libraryPathArray;
        this.libraryPathForEmulation = preset.libraryPathForEmulationArray;
        this.fmodLibraryPath = preset.fmodLibraryPath;
        this.extraJvmArgs = preset.extraJvmArgs;
        this.args = preset.args;
        this.mainClassName = preset.mainClassName;
        this.javaAgentPath = preset.javaAgentPath;
        this.javaAgentArgs = preset.javaAgentArgs;
    }

    private static String buildHomePath(String name) {
        return AppStorage.requireSingleton().getHomePath() + "/" + INSTANCES_ROOT_DIR_NAME + "/" + name;
    }

    public static boolean isValidName(String name) {
        return FileUtils.isValidFilenameStrict(name);
    }

    public static boolean isUniqueName(String name) {
        return !new File(buildHomePath(name)).exists();
    }

    public String getName() {
        return this.name;
    }

  public String getBuildVersion() { return this.buildVersion; }


  public String getHomePath() {
        return this.homePath;
    }

    public String getGamePath() {
        return this.homePath + "/" + GAME_FILES_DIR_NAME;
    }

    public String getLdLibraryPathForEmulation() {
        StringJoiner joiner = new StringJoiner(":");
        for (String path : this.libraryPathForEmulation) {
            joiner.add(AppStorage.requireSingleton().getHomePath() + "/" + path);
        }
        return joiner + ":.";
    }

    public String getFmodLibraryPath() {
        return fmodLibraryPath;
    }

    public String getJavaLibraryPath() {
        StringJoiner libsJoiner = new StringJoiner(":");
        for (String path : this.libraryPath) {
            libsJoiner.add(AppStorage.requireSingleton().getHomePath() + "/" + path);
        }
        return libsJoiner.toString();
    }

    public ArrayList<String> getJvmArgsAsList() { 
        ArrayList<String> jvmArgsList = new ArrayList<>();
        jvmArgsList.add("-Duser.home=" + this.homePath);
        jvmArgsList.add("-Djava.io.tmpdir=" + AppStorage.requireSingleton().getCachePath());

        jvmArgsList.add("-Djava.library.path=" + getJavaLibraryPath() + ":.");

        jvmArgsList.add("-Dorg.lwjgl.util.Debug=true"); // debug
        jvmArgsList.add("-Dorg.lwjgl.util.DebugLoader=true"); // debug
        StringJoiner jarsJoiner = new StringJoiner(":");
        for (String path : this.extraClassPath) {
            jarsJoiner.add(AppStorage.requireSingleton().getHomePath() + "/" + path);
        }
        jvmArgsList.add("-Djava.class.path=" + String.join(":", this.classPath) + ":" + jarsJoiner);

        jvmArgsList.addAll(Arrays.asList(this.extraJvmArgs));

        if (!this.javaAgentPath.isEmpty()) {
            StringBuilder agentBuilder = new StringBuilder();
            agentBuilder.append("-javaagent:").append(AppStorage.requireSingleton().getHomePath() + "/" + this.javaAgentPath);
            if (!this.javaAgentArgs.isEmpty()) {
                agentBuilder.append("=").append(this.javaAgentArgs);
            }
            jvmArgsList.add(agentBuilder.toString());
        }

        return jvmArgsList;
    }

    public String[] getClassPathArray() {
        return this.classPath;
    }

    public ArrayList<String> getArgsAsList() {
        return new ArrayList<>(Arrays.asList(this.args));
    }

    public String getMainClassName() {
        return this.mainClassName;
    }

    private void makeDirs() throws FileSystemException {
        File homeDir = new File(buildHomePath(this.name));
        if (!homeDir.mkdirs()) {
            throw new FileSystemException("Failed to create instance directory " + homeDir.getAbsolutePath());
        }
        this.homePath = homeDir.getAbsolutePath();

        File gameDir = new File(getGamePath());
        if (!gameDir.mkdirs()) {
            throw new FileSystemException("Failed to create game files directory " + gameDir.getAbsolutePath());
        }
    }

    public boolean isInstallationFinished() {
        return this.installationFinished;
    }

    protected void markInstallationFinished() {
        this.installationFinished = true;
    }

    public boolean hasGameFiles() {
        // New fat-jar structure (42.12+)
        for (String cp : getClassPathArray()) {
            if ("projectzomboid.jar".equals(cp)) {
                File jar = new File(getGamePath(), "projectzomboid.jar");
                return jar.exists();
            }
        }

        // Old structure (41 / 42.6 - 42.11)
        File mainClassFile = new File(getGamePath() + "/" + getMainClassName() + ".class");
        return mainClassFile.exists();
    }

    public boolean hasFilesForLinux() {
        File pzBulletFile = new File(getGamePath() + "/libPZBullet64.so");
        return pzBulletFile.exists();
    }
}
