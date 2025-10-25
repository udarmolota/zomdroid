package com.zomdroid;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.system.ErrnoException;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.zomdroid.input.GLFWBinding;
import com.zomdroid.input.GamepadManager;
import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.databinding.ActivityGameBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.input.AbstractControlElement;
import com.zomdroid.input.InputControlsView;

import org.fmod.FMOD;

import java.lang.ref.WeakReference;


/**
 * Main game activity. Handles UI, surface, and input.
 * Integrates GamepadManager for hotplug and routes all gamepad input to the native interface.
 * Hides the virtual controller UI when a physical gamepad is connected.
 */
public class GameActivity extends AppCompatActivity implements GamepadManager.GamepadListener {
    public static final String EXTRA_GAME_INSTANCE_NAME = "com.zomdroid.GameActivity.EXTRA_GAME_INSTANCE_NAME";
    private static final String LOG_TAG = GameActivity.class.getName();

    private ActivityGameBinding binding;
    private Surface gameSurface;
    private static boolean isGameStarted = false;
    private GestureDetector gestureDetector;

    // Handles all gamepad connection/disconnection and input events
    private GamepadManager gamepadManager;
    // Tracks whether a physical gamepad is currently connected (for UI logic)
    private boolean isGamepadConnected = false;

    @SuppressLint({"UnsafeDynamicallyLoadedCode", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityGameBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize and register GamepadManager for gamepad hotplug and input events
        try {
            gamepadManager = new GamepadManager(this, this);
            gamepadManager.register();

          // Apply touch override based on saved preference
          boolean isTouchEnabled = LauncherPreferences.requireSingleton().isTouchControlsEnabled();
          GamepadManager.setTouchOverride(isTouchEnabled);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to initialize GamepadManager", e);
            gamepadManager = null;
        }

        getWindow().setDecorFitsSystemWindows(false);
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        String gameInstanceName = getIntent().getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null)
            throw new RuntimeException("Expected game instance name to be passed as intent extra");
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null)
            throw new RuntimeException("Game instance with name " + gameInstanceName + " not found");

        System.loadLibrary("zomdroid");

        System.load(AppStorage.requireSingleton().getHomePath() + "/" + gameInstance.getFmodLibraryPath() + "/libfmod.so");
        System.load(AppStorage.requireSingleton().getHomePath() + "/" + gameInstance.getFmodLibraryPath() + "/libfmodstudio.so");
/*        System.loadLibrary("fmod");
        System.loadLibrary("fmodstudio");*/

        FMOD.init(this);

/*        gestureDetector = new GestureDetector(this, new GestureDetector.OnGestureListener() {
            private boolean showPress = false;
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                Log.v("", "onDown " + e.getX() + " " + e.getY());
                //InputBridge.sendMouseButton(GLFWConstants.GLFW_MOUSE_BUTTON_LEFT, GLFWConstants.GLFW_PRESS, event.getX(), event.getY());
                return true;
            }

            @Override
            public void onShowPress(@NonNull MotionEvent e) {
                Log.v("", "onShowPress " + e.getX() + " " + e.getY());
                showPress = true;
                InputNativeInterface.sendCursorPos(e.getX(), e.getY());
                InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                Log.v("", "onSingleTapUp " + e.getX() + " " + e.getY());
                InputNativeInterface.sendCursorPos(e.getX(), e.getY());
                if (showPress) {
                    InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                }
                showPress = false;
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                InputNativeInterface.sendCursorPos(e2.getX(), e2.getY());
                Log.v("", "onScroll " + (e1 == null ? "0" : e1.getX()) + " " + (e1 == null ? "0" : e1.getY()) + " " + e2.getX() + " " + e2.getY());
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                Log.v("", "onLongPress " + e.getX() + " " + e.getY());
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                Log.v("", "onFling " + velocityX + " " + velocityY);
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);*/

        binding.gameSv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface created.");
                float renderScale = LauncherPreferences.requireSingleton().getRenderScale();
                int width = (int) (binding.gameSv.getWidth() * renderScale);
                int height = (int) (binding.gameSv.getHeight() * renderScale);
                binding.gameSv.getHolder().setFixedSize(width, height);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(LOG_TAG, "Game surface changed.");


                gameSurface = binding.gameSv.getHolder().getSurface();
                if (gameSurface == null) throw new RuntimeException();

                if (format != PixelFormat.RGBA_8888) {
                    Log.w(LOG_TAG, "Using unsupported pixel format " + format); // LIAMELUI seems like default is RGB_565
                }

                GameLauncher.setSurface(gameSurface, width, height);
                if (!isGameStarted) {
                    Thread thread = new Thread(() -> {
                        try {
                            GameLauncher.launch(gameInstance);
                        } catch (ErrnoException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    thread.start();
                    isGameStarted = true;
                }
                //binding.inputControlsV.applyInputMode(binding.inputControlsV.getCurrentInputMode());
                //System.out.println("[mixed b] surfaceChanged → reapply input mode: " + binding.inputControlsV.getCurrentInputMode());
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface destroyed.");
                GameLauncher.destroySurface();
            }
        });

        binding.gameSv.setOnTouchListener(new View.OnTouchListener() {
            float renderScale = LauncherPreferences.requireSingleton().getRenderScale();
            int pointerId = -1;

            @Override
            public boolean onTouch(View v, MotionEvent e) { // this should be in InputControlsView
                int action = e.getActionMasked();
                int actionIndex = e.getActionIndex();
                int pointerId = e.getPointerId(actionIndex);
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN: {
                        float x = e.getX(actionIndex);
                        float y = e.getY(actionIndex);
                        this.pointerId = pointerId;
                        InputNativeInterface.sendCursorPos(x * this.renderScale, y * this.renderScale);
                        InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (this.pointerId < 0) return false;
                        int pointerIndex = e.findPointerIndex(this.pointerId);
                        if (pointerIndex < 0) {
                            this.pointerId = -1;
                            return false;
                        }
                        float x = e.getX(pointerIndex);
                        float y = e.getY(pointerIndex);
                        InputNativeInterface.sendCursorPos(x * this.renderScale, y * this.renderScale);
                        return false;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (pointerId != this.pointerId) return false;
                        this.pointerId = -1;
                        InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                        return true;
                    }
                }
                return false;
            }
        });

        // Initial state: assume no gamepad connected until GamepadManager notifies otherwise
        isGamepadConnected = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister GamepadManager to avoid leaks
        if (gamepadManager != null) {
            gamepadManager.unregister();
        }
    }

    // GamepadManager.GamepadListener implementation

    // Called when any physical gamepad is connected: hide the virtual controller UI
    @Override
    public void onGamepadConnected() {
        isGamepadConnected = true;

        if (binding.inputControlsV != null) {
            System.out.println("[mixed b] onGamepadConnected");
            binding.inputControlsV.setVisibility(View.VISIBLE);
            binding.inputControlsV.setGamepadConnected(true);
            binding.inputControlsV.applyInputMode(InputControlsView.InputMode.MNK);
        }
        //if (binding.inputControlsV != null) {
        //    binding.inputControlsV.setVisibility(View.GONE);
        //}
    }

    // Called when all physical gamepads are disconnected: show the virtual controller UI
    @Override
    public void onGamepadDisconnected() {
        isGamepadConnected = false;

        if (binding.inputControlsV != null) {
            System.out.println("[mixed b] onGamepadDisconnected");
            binding.inputControlsV.setVisibility(View.VISIBLE);
            binding.inputControlsV.setGamepadConnected(false);
            binding.inputControlsV.applyInputMode(InputControlsView.InputMode.ALL);
        }
    }

    // Forward every gamepad button event to the native input interface
    @Override
    public void onGamepadButton(int button, boolean pressed) {
        InputNativeInterface.sendJoystickButton(button, pressed);
    }

    // Forward every gamepad axis event to the native input interface
    @Override
    public void onGamepadAxis(int axis, float value) {
        InputNativeInterface.sendJoystickAxis(axis, value);
    }

    // Forward every gamepad dpad event to the native input interface
    @Override
    public void onGamepadDpad(int dpad, char state) {
        InputNativeInterface.sendJoystickDpad(dpad, state);
    }

    // Handle gamepad key events
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (gamepadManager != null && gamepadManager.handleKeyEvent(event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (gamepadManager != null && gamepadManager.handleKeyEvent(event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // Handle gamepad motion events
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gamepadManager != null && gamepadManager.handleMotionEvent(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
}
