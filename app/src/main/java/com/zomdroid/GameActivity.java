package com.zomdroid;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.util.Log;

import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import android.widget.Toast;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.zomdroid.input.GLFWBinding;
import com.zomdroid.input.GamepadManager;
import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.databinding.ActivityGameBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.input.InputControlsView;
import com.zomdroid.input.KeyboardManager;
import com.zomdroid.input.AbstractControlElement;

import org.fmod.FMOD;

/**
 * Main game activity. Handles UI, surface, and input.
 * Integrates GamepadManager for hotplug and routes all gamepad input to the native interface.
 * Hides the virtual controller UI when a physical gamepad is connected.
 */
public class GameActivity extends AppCompatActivity implements GamepadManager.GamepadListener, KeyboardManager.KeyboardListener {
    public static final String EXTRA_GAME_INSTANCE_NAME = "com.zomdroid.GameActivity.EXTRA_GAME_INSTANCE_NAME";
    private static final String LOG_TAG = GameActivity.class.getName();

    private ActivityGameBinding binding;
    private Surface gameSurface;
    private static boolean isGameStarted = false;

    // Handles all gamepad connection/disconnection and input events
    private GamepadManager gamepadManager;
    private KeyboardManager keyboardManager;

    // Tracks whether a physical gamepad/kb is currently connected (for UI logic)
    private boolean isGamepadConnected = false;
    private boolean isKeyboardConnected = false;

    private boolean leftMouseDown  = false;
    private boolean rightMouseDown = false;

    // Helps to calculate mouse cursor position
    private float renderScale = 1f;

    private static final long LMB_TAP_RELEASE_DELAY_MS = 40;
    private final Runnable lmbAutoUp = new Runnable() {
      @Override public void run() {
        if (leftMouseDown) {
          leftMouseDown = false;
          InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
        }
      }
    };

    //private void dbg(String s) {
    //  runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    //}

    @SuppressLint({"UnsafeDynamicallyLoadedCode", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityGameBinding.inflate(getLayoutInflater());
        // Give focus to game surface to ensure it receives input events
        setContentView(binding.getRoot());
        binding.gameSv.setFocusable(true);
        binding.gameSv.setFocusableInTouchMode(true);
        binding.gameSv.requestFocus();

        // Initializing the cursor calsulation pos helper
        renderScale = LauncherPreferences.requireSingleton().getRenderScale();

        // Initialize and register GamepadManager for gamepad hotplug and input events
        try {
            gamepadManager = new GamepadManager(this, this);
            //gamepadManager.register();

            // Apply touch override based on saved preference
            boolean isTouchEnabled = LauncherPreferences.requireSingleton().isTouchControlsEnabled();
            GamepadManager.setTouchOverride(isTouchEnabled);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to initialize GamepadManager", e);
            gamepadManager = null;
        }
        // Initialize KeyboardManager
        try {
            keyboardManager = new KeyboardManager(this, this);
            //keyboardManager.register();

          // Apply touch override based on saved preference
          boolean isTouchEnabled = LauncherPreferences.requireSingleton().isTouchControlsEnabled();
          KeyboardManager.setTouchOverride(isTouchEnabled);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to initialize keyboardManager", Toast.LENGTH_SHORT).show();
            keyboardManager = null;
        }
        // Display on/off buttons overlay
        applyInputOverlay();

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

        FMOD.init(this);

        binding.gameSv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface created.");
                renderScale = LauncherPreferences.requireSingleton().getRenderScale();
                int width = (int) (binding.gameSv.getWidth() * renderScale);
                int height = (int) (binding.gameSv.getHeight() * renderScale);
                binding.gameSv.getHolder().setFixedSize(width, height);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(LOG_TAG, "Game surface changed.");
                gameSurface = binding.gameSv.getHolder().getSurface();
                //gameSurface = holder.getSurface();
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
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface destroyed.");
                GameLauncher.destroySurface();
            }
        });

      binding.gameSv.setOnTouchListener(new View.OnTouchListener() {
        //float renderScale = LauncherPreferences.requireSingleton().getRenderScale();
        int activePointerId = -1;
        boolean leftPressedFinger = false;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
          int action = e.getActionMasked();
          int idx = e.getActionIndex();

          switch (action) {
              case MotionEvent.ACTION_DOWN:
              case MotionEvent.ACTION_POINTER_DOWN: {
                activePointerId = e.getPointerId(idx);
                float x = e.getX(idx), y = e.getY(idx);
                InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);

                leftPressedFinger = true;
                leftMouseDown = true;
                InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
                return true;
              }
              case MotionEvent.ACTION_MOVE: {
                if (activePointerId < 0) return false;
                int p = e.findPointerIndex(activePointerId);
                if (p < 0) { activePointerId = -1; return false; }
                float x = e.getX(p), y = e.getY(p);
                //dbg("ACTION_MOVE");
                InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
                return true;
              }
              case MotionEvent.ACTION_UP:
              case MotionEvent.ACTION_POINTER_UP: {
                if (activePointerId < 0) return false;
                float x = e.getX(idx), y = e.getY(idx);
                if (leftPressedFinger) {
                  InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                  leftPressedFinger = false;
                }
                leftMouseDown = false;
                InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
                activePointerId = -1;
                return true;
              }
              case MotionEvent.ACTION_CANCEL: {
                if (leftPressedFinger || leftMouseDown) {
                    InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                    leftPressedFinger = false;
                    leftMouseDown = false;
                }
                activePointerId = -1;
                return true;
            }
          }
          return false;
        }
      });
    }

    @Override
    protected void onDestroy() {
      super.onDestroy();
      // Unregister GamepadManager to avoid leaks
      if (gamepadManager != null) {
          gamepadManager.unregister();
      }

      // Unregister Keyboard to avoid leaks
      if (keyboardManager != null) {
          keyboardManager.unregister();
      }
    }

    // GamepadManager.GamepadListener implementation

    // Called when any physical gamepad is connected: hide the virtual controller UI
    @Override
    public void onGamepadConnected() {
        isGamepadConnected = true;
        applyInputOverlay();
    }

    // Called when all physical gamepads are disconnected: show the virtual controller UI
    @Override
    public void onGamepadDisconnected() {
        isGamepadConnected = false;
        applyInputOverlay();
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
        boolean handled = false;
        if (isKeyboardConnected && (keyboardManager != null)) handled |= keyboardManager.handleKeyEvent(event);
        if (isGamepadConnected && (gamepadManager  != null)) handled |= gamepadManager.handleKeyEvent(event);
        if (handled) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean handled = false;
        if (isKeyboardConnected && (keyboardManager != null)) handled |= keyboardManager.handleKeyEvent(event);
        if (isGamepadConnected && (gamepadManager  != null)) handled |= gamepadManager.handleKeyEvent(event);
        if (handled) return true;
        return super.onKeyUp(keyCode, event);
    }


    // Handle gamepad/keyboard motion events
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
      //float renderScale = LauncherPreferences.requireSingleton().getRenderScale();

      boolean isPointerDevice = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;

      if (!isPointerDevice) {
          if (gamepadManager != null && gamepadManager.handleMotionEvent(event)) return true;
          return super.onGenericMotionEvent(event);
      }

      int action = event.getActionMasked();
      int btn = event.getActionButton();

      // Cursor movement: always update position and if LMB/RMB is held — it's a drag of crosshair/objects
      //if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) {
      if (action == MotionEvent.ACTION_HOVER_MOVE) {
        float x = event.getX();
        float y = event.getY();
        InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);

        if (leftMouseDown || rightMouseDown) {
          //dbg("DRAG move");
          return true;
        }
        return true;
      }

      if (action == MotionEvent.ACTION_SCROLL) {
        float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (v == 0) { v = event.getAxisValue(MotionEvent.AXIS_WHEEL); }

        if (v != 0) {
          int keyCode = v > 0 ? GLFWBinding.KEY_EQUAL.code : GLFWBinding.KEY_MINUS.code;
          InputNativeInterface.sendKeyboard(keyCode, true);
          //try { Thread.sleep(50); } catch (InterruptedException e) {}
          //InputNativeInterface.sendKeyboard(keyCode, false);
          binding.getRoot().postDelayed(() -> {
                InputNativeInterface.sendKeyboard(keyCode, false);
            }, 50);
        }
        return true;
      }

      if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
          boolean pressed = (action == MotionEvent.ACTION_BUTTON_PRESS);
          InputNativeInterface.sendCursorPos(event.getX() * renderScale, event.getY() * renderScale);

        if (btn == MotionEvent.BUTTON_PRIMARY) {
          //dbg(pressed ? "LMB PRESS" : "LMB RELEASE");
          leftMouseDown = pressed;
          InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, pressed);
          return true;
        } else if (btn == MotionEvent.BUTTON_SECONDARY) {
          //dbg(pressed ? "RMB PRESS" : "RMB RELEASE");
          rightMouseDown = pressed;
          InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_RIGHT.code, pressed);
          return true;
        }
      }
      return super.onGenericMotionEvent(event);
    }

    @Override
    public void onKeyboardConnected() {
        isKeyboardConnected = true;
        applyInputOverlay();
        //Toast.makeText(this, "onKeyboardConnected()", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onKeyboardDisconnected() {
        isKeyboardConnected = false;
        applyInputOverlay();
        //Toast.makeText(this, "onKeyboardDisconnected()", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onKeyboardKey(int glfwCode, boolean pressed) {
      InputNativeInterface.sendKeyboard(glfwCode, pressed);
    }

    private void applyInputOverlay() {
      if (binding.inputControlsV == null) return;
      binding.inputControlsV.setGamepadConnected(isGamepadConnected);

      if (isKeyboardConnected) {
        binding.inputControlsV.setVisibility(View.GONE);
      } else if (isGamepadConnected) {
        binding.inputControlsV.setVisibility(View.VISIBLE);
        binding.inputControlsV.applyInputMode(InputControlsView.InputMode.MNK);
      } else {
        binding.inputControlsV.setVisibility(View.VISIBLE);
        binding.inputControlsV.applyInputMode(InputControlsView.InputMode.ALL);
      }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gamepadManager != null)  gamepadManager.register();
        if (keyboardManager != null) keyboardManager.register();
    }

    @Override
    protected void onPause() {
        if (gamepadManager != null)  gamepadManager.unregister();
        if (keyboardManager != null) keyboardManager.unregister();
        super.onPause();
    }
}
