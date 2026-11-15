package com.winlator.fexcore;

import app.gamenative.R;

import android.content.Context;
import com.winlator.container.Shortcut;

import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.container.Container;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.FileUtils;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import timber.log.Timber;

public final class FEXCoreManager {

    private static final String[] APP_CONFIG_EXE_NAMES = {
        "RockstarService.exe",
        "RockstarSteamHelper.exe",
        "SocialClubHelper.exe",
        "UplayWebCore.exe",
        "steamservice.exe",
        "steamwebhelper.exe",
        "steam.exe",
    };

    private static final String[][] APP_CONFIG_OPTIONS = {
        {"Multiblock", "0"},
        {"X87ReducedPrecision", "0"},
        {"TSOEnabled", "1"},
        {"VectorTSOEnabled", "1"},
        {"MemcpySetTSOEnabled", "1"},
        {"HalfBarrierTSOEnabled", "1"},
        {"MonoHacks", "0"},
        {"EnableCodeCachingWIP", "0"},
    };

    static final String APP_CONFIG_CONTENT = appConfigContent();

    private static final String CONTAINER_CONFIG_PATH = ".fex-emu/Config.json";

    // Copy of the Config.json this launcher wrote; a Config.json that does not match it is the user's.
    private static final String CONTAINER_CONFIG_RECORD_PATH = ".fex-emu/.Config.json.launcher";

    private static final String[] CONFIG_LOCATION_ENV_VARS = {"FEX_APP_CONFIG_LOCATION", "FEX_PORTABLE"};

    FEXCoreManager() {
    }

    private static String appConfigContent() {
        EnvVars options = new EnvVars();
        for (String[] option : APP_CONFIG_OPTIONS) options.put(envName(option[0]), option[1]);
        return containerConfigContent(options);
    }

    private static String envName(String option) {
        return "FEX_" + option.toUpperCase(Locale.ROOT);
    }

    /**
     * FEX ranks FEX_* environment variables above the per-app files, so an option those files set
     * only reaches them when every other process gets it from the container's Config.json instead.
     * Removes those options from envVars and returns them.
     */
    public static EnvVars takeAppConfigOptions(EnvVars envVars) {
        EnvVars taken = new EnvVars();
        for (String[] option : APP_CONFIG_OPTIONS) {
            String name = envName(option[0]);
            if (!envVars.has(name)) continue;
            taken.put(name, envVars.get(name));
            envVars.remove(name);
        }
        return taken;
    }

    /** Config.json content for options named by their FEX_* environment variables. */
    public static String containerConfigContent(EnvVars options) {
        StringBuilder json = new StringBuilder("{\n  \"Config\": {");
        String separator = "\n";
        for (String[] option : APP_CONFIG_OPTIONS) {
            String name = envName(option[0]);
            if (!options.has(name)) continue;
            json.append(separator).append("    ").append(JSONObject.quote(option[0])).append(": ").append(JSONObject.quote(options.get(name)));
            separator = ",\n";
        }
        return json.append("\n  }\n}\n").toString();
    }

    /** True when these variables make FEX read its config from somewhere other than the container home. */
    public static boolean configLocationOverridden(EnvVars envVars) {
        for (String name : CONFIG_LOCATION_ENV_VARS) {
            if (envVars.has(name)) return true;
        }
        return false;
    }

    static boolean isOwnConfig(String config, String record) {
        return config != null && config.equals(record);
    }

    private static String readIfFile(File file) {
        if (!file.isFile()) return null;
        byte[] data = FileUtils.read(file);
        return data != null ? new String(data, StandardCharsets.UTF_8) : null;
    }

    private static boolean ownsContainerConfig(File containerRootDir) {
        return isOwnConfig(readIfFile(new File(containerRootDir, CONTAINER_CONFIG_PATH)),
            readIfFile(new File(containerRootDir, CONTAINER_CONFIG_RECORD_PATH)));
    }

    /** False when the container has a Config.json this launcher did not write. */
    public static boolean canWriteContainerConfig(File containerRootDir) {
        return !new File(containerRootDir, CONTAINER_CONFIG_PATH).exists() || ownsContainerConfig(containerRootDir);
    }

    public static boolean writeContainerConfig(File containerRootDir, EnvVars options) {
        if (!canWriteContainerConfig(containerRootDir)) return false;
        String content = containerConfigContent(options);
        if (!FileUtils.writeString(new File(containerRootDir, CONTAINER_CONFIG_RECORD_PATH), content)) return false;
        if (!FileUtils.writeString(new File(containerRootDir, CONTAINER_CONFIG_PATH), content)) return false;
        Timber.i("FEX container config: %s", content.replaceAll("\\s+", " "));
        return true;
    }

    /** Deletes the container Config.json only when this launcher wrote it. */
    public static void deleteOwnContainerConfig(File containerRootDir) {
        if (ownsContainerConfig(containerRootDir)) new File(containerRootDir, CONTAINER_CONFIG_PATH).delete();
        new File(containerRootDir, CONTAINER_CONFIG_RECORD_PATH).delete();
    }

    public static File ensureAppConfigOverrides(Context context) {
        try {
            ImageFs imageFs = ImageFs.find(context);
            File rootDir = imageFs.getRootDir();
            File baseDir = new File(rootDir, "/home/xuser/.fex-emu");
            File appConfigDir = new File(baseDir, "AppConfig");

            for (String exeName : APP_CONFIG_EXE_NAMES) {
                File appConfigFile = new File(appConfigDir, exeName + ".json");
                FileUtils.writeString(appConfigFile, APP_CONFIG_CONTENT);
            }

            return baseDir;
        } catch (Exception e) {
            Timber.e(e, "Failed to write FEX app config overrides");
            return null;
        }
    }

    public static String findAppConfigExeName(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) return null;
        String lowerCommand = commandLine.toLowerCase(Locale.ENGLISH);
        for (String exeName : APP_CONFIG_EXE_NAMES) {
            if (lowerCommand.contains(exeName.toLowerCase(Locale.ENGLISH))) {
                return exeName;
            }
        }
        return null;
    }

    public static void loadFEXCoreVersion(Context context, ContentsManager contentsManager, Spinner spinner, Container container) {
        String[] originalItems = context.getResources().getStringArray(R.array.fexcore_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        if (container != null)
            AppUtils.setSpinnerSelectionFromValue(spinner, container.getFEXCoreVersion());
        else
            AppUtils.setSpinnerSelectionFromValue(spinner, DefaultVersion.FEXCORE);
    }

    public static void loadFEXCoreVersion(Context context, ContentsManager contentsManager, Spinner spinner, Shortcut shortcut) {
        String[] originalItems = context.getResources().getStringArray(R.array.fexcore_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        AppUtils.setSpinnerSelectionFromValue(spinner, shortcut.getExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion()));
    }
}
