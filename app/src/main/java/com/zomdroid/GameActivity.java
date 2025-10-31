package com.zomdroid;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.system.ErrnoException;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputDevice;
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
    private GestureDetector gestureDetector;

    // Handles all gamepad connection/disconnection and input events
    private GamepadManager gamepadManager;
    private KeyboardManager keyboardManager;
    // Tracks whether a physical gamepad/kb is currently connected (for UI logic)
    private boolean isGamepadConnected = false;
    private boolean isKeyboardConnected = false;

    private void t(String msg) {
      runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @SuppressLint({"UnsafeDynamicallyLoadedCode", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityGameBinding.inflate(getLayoutInflater());
        // Give focus to game surface to ensure it receives input events
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
        // Initialize KeyboardManager
        try {
            keyboardManager = new KeyboardManager(this, this);
            keyboardManager.register();

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
        int activePointerId = -1;
        boolean leftPressed = false;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
          boolean isPointerDevice = e.isFromSource(InputDevice.SOURCE_MOUSE) || e.isFromSource(InputDevice.SOURCE_TOUCHPAD)
              || e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;

          if (isPointerDevice) {
            //Toast.makeText(v.getContext(), "[onTouch] pointer device -> pass", Toast.LENGTH_SHORT).show();
            return false;
          }

          int action = e.getActionMasked();
          int idx = e.getActionIndex();

          switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
              activePointerId = e.getPointerId(idx);
              float x = e.getX(idx), y = e.getY(idx);
              InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
              //Toast.makeText(v.getContext(), "LEFT DOWN @" + (int)x + "," + (int)y, Toast.LENGTH_SHORT).show();
              leftPressed = true;
              InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
              return true;
            }
            case MotionEvent.ACTION_MOVE: {
              if (activePointerId < 0) return false;
              int p = e.findPointerIndex(activePointerId);
              if (p < 0) { activePointerId = -1; return false; }
              float x = e.getX(p), y = e.getY(p);
              if (leftPressed) {
                // just keep pressed — GLFW assume as drag
              }
              InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
              return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
              if (activePointerId < 0) return false;
              float x = e.getX(idx), y = e.getY(idx);
              if (leftPressed) {
                InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                //Toast.makeText(v.getContext(), "LEFT UP @" + (int)x + "," + (int)y, Toast.LENGTH_SHORT).show();
              }
              leftPressed = false;
              InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale); // безопасно синхронизируем
              activePointerId = -1;
              //Toast.makeText(v.getContext(), "[onTouch] FINGER UP -> LEFT", Toast.LENGTH_SHORT).show();
              //InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
              return true;
            }
          }
          return false;
        }
      });

      // Initial state: assume no gamepad connected until GamepadManager notifies otherwise
      //isGamepadConnected = false;
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
        //if (binding.inputControlsV != null) {
        //    System.out.println("[mixed b] onGamepadConnected");
        //    binding.inputControlsV.setVisibility(View.VISIBLE);
        //    binding.inputControlsV.setGamepadConnected(true);
        //    binding.inputControlsV.applyInputMode(InputControlsView.InputMode.MNK);
        //}
        //if (binding.inputControlsV != null) {
        //    binding.inputControlsV.setVisibility(View.GONE);
        //}
    }

    // Called when all physical gamepads are disconnected: show the virtual controller UI
    @Override
    public void onGamepadDisconnected() {
        isGamepadConnected = false;
        applyInputOverlay();
        //if (binding.inputControlsV != null) {
        //    System.out.println("[mixed b] onGamepadDisconnected");
        //    binding.inputControlsV.setVisibility(View.VISIBLE);
        //    binding.inputControlsV.setGamepadConnected(false);
        //    binding.inputControlsV.applyInputMode(InputControlsView.InputMode.ALL);
        //}
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
      float renderScale = LauncherPreferences.requireSingleton().getRenderScale();

      boolean isPointerDevice = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
        event.isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
        event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;

      if (!isPointerDevice) {
          if (gamepadManager != null && gamepadManager.handleMotionEvent(event)) return true;
          return super.onGenericMotionEvent(event);
      }

      int action = event.getActionMasked();
      int btn = event.getActionButton();
      int mask = event.getButtonState();

      if (action == MotionEvent.ACTION_HOVER_MOVE) {
          InputNativeInterface.sendCursorPos(event.getX() * renderScale, event.getY() * renderScale);

          // If 'pressed' it's drag
          if ((mask & MotionEvent.BUTTON_PRIMARY) != 0 || (mask & MotionEvent.BUTTON_SECONDARY) != 0) {
            return true; // drag обработан
          }
          return true;
      }

      if (action == MotionEvent.ACTION_SCROLL) {
        // float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        // float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
        // Toast.makeText(this, "SCROLL v="+v+" h="+h, Toast.LENGTH_SHORT).show();
        return true;
      }

      if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
          boolean pressed = (action == MotionEvent.ACTION_BUTTON_PRESS);
          InputNativeInterface.sendCursorPos(event.getX() * renderScale, event.getY() * renderScale);

        if (btn == MotionEvent.BUTTON_PRIMARY) {
            //Toast.makeText(this, "PRIMARY ignored", Toast.LENGTH_SHORT).show();
            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, pressed);
            return true;
        } else if (btn == MotionEvent.BUTTON_SECONDARY) {
            //Toast.makeText(this, "RIGHT " + (pressed?"DOWN":"UP"), Toast.LENGTH_SHORT).show();
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
        //if (binding.inputControlsV != null) {
        //    binding.inputControlsV.setVisibility(View.GONE);
        //}
    }

    @Override
    public void onKeyboardDisconnected() {
        isKeyboardConnected = false;
        applyInputOverlay();
        //Toast.makeText(this, "onKeyboardDisconnected()", Toast.LENGTH_SHORT).show();
        //if (binding.inputControlsV != null) {
        //    binding.inputControlsV.setVisibility(View.VISIBLE);
        //    binding.inputControlsV.applyInputMode(InputControlsView.InputMode.ALL);
        //}
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
}
