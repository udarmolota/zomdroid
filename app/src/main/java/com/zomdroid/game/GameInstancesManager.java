package com.zomdroid.game;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zomdroid.C;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class GameInstancesManager {
    private static GameInstancesManager singleton;
    private ArrayList<GameInstance> gameInstances = new ArrayList<>();
    private Gson gson = new Gson();
    private SharedPreferences sharedPreferences;


    private GameInstancesManager(Context applicationContext) {
        this.sharedPreferences = applicationContext.getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
        loadInstancesFromDisk();
    }
    public static void init(Context applicationContext) {
        singleton = new GameInstancesManager(applicationContext);
    }

    public static GameInstancesManager getSingleton() {
        return singleton;
    }

    @NonNull
    public static GameInstancesManager requireSingleton() {
        if (singleton == null) {
            throw new RuntimeException("GameInstancesManager is not initialized");
        }
        return singleton;
    }

    @NonNull
    public ArrayList<GameInstance> getInstances() {
        return gameInstances;
    }

    public GameInstance getInstanceByName(String name) {
        for (GameInstance gameInstance : this.gameInstances) {
            if (gameInstance.getName().equals(name))
                return gameInstance;
        }
        return null;
    }

    public void registerInstance(@NonNull GameInstance gameInstance) {
        this.gameInstances.add(gameInstance);
        saveInstancesToDisk();
    }

    public void unregisterInstance(@NonNull GameInstance gameInstance) {
        this.gameInstances.remove(gameInstance);
        saveInstancesToDisk();
    }

    public void setInstanceInstalled(@NonNull GameInstance gameInstance) {
        gameInstance.setInstalled(true);
        saveInstancesToDisk();
    }

    private void loadInstancesFromDisk() {
        String json = this.sharedPreferences.getString(C.shprefs.keys.GAME_INSTANCES, null);
        if (json != null) {
            Type type = new TypeToken<ArrayList<GameInstance>>(){}.getType();
            ArrayList<GameInstance> savedInstances = gson.fromJson(json, type);
            if (savedInstances != null) {
                gameInstances.addAll(savedInstances);
            }
        }
    }

    private void saveInstancesToDisk() {
        String json = this.gson.toJson(this.gameInstances);
        this.sharedPreferences.edit().putString(C.shprefs.keys.GAME_INSTANCES, json).apply();
    }

}
