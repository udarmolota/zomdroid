package com.zomdroid.game;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.zomdroid.C;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class GameInstanceManager {
    private static GameInstanceManager singleton;
    private ArrayList<GameInstance> gameInstances = new ArrayList<>();
    private Gson gson = new Gson();
    private SharedPreferences sharedPreferences;

    private GameInstanceManager(Context applicationContext) {
        this.sharedPreferences = applicationContext.getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
        loadFromPreferences();
    }

    public static void init(Context applicationContext) {
        singleton = new GameInstanceManager(applicationContext);
    }

    public static GameInstanceManager getSingleton() {
        return singleton;
    }

    @NonNull
    public static GameInstanceManager requireSingleton() {
        if (singleton == null) {
            throw new RuntimeException("GameInstanceManager is not initialized");
        }
        return singleton;
    }

    @NonNull
    public ArrayList<GameInstance> getInstances() {
        return gameInstances;
    }

    public GameInstance getInstanceByName(String name) {
        for (GameInstance gameInstance : this.gameInstances) {
            if (gameInstance.getName().equals(name)) return gameInstance;
        }
        return null;
    }

    public void registerInstance(@NonNull GameInstance gameInstance) {
        this.gameInstances.add(gameInstance);
        saveToPreferences();
    }

    public void unregisterInstance(@NonNull GameInstance gameInstance) {
        this.gameInstances.remove(gameInstance);
        saveToPreferences();
    }

    public void markInstallationFinished(@NonNull GameInstance gameInstance) {
        gameInstance.markInstallationFinished();
        saveToPreferences();
    }

    private void loadFromPreferences() {
        String json = this.sharedPreferences.getString(C.shprefs.keys.GAME_INSTANCES, null);
        if (json != null) {
            JsonArray rawArray = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : rawArray) {
                JsonObject obj = element.getAsJsonObject();

                GameInstance instance = gson.fromJson(obj, GameInstance.class);

                if (obj.has("isInstalled")) { // compat v1.1.0
                    boolean isInstalled = obj.get("isInstalled").getAsBoolean();
                    if (isInstalled) instance.markInstallationFinished();
                }

                gameInstances.add(instance);
            }
        }
    }

    private void saveToPreferences() {
        String json = this.gson.toJson(this.gameInstances);
        this.sharedPreferences.edit().putString(C.shprefs.keys.GAME_INSTANCES, json).apply();
    }
}
