package com.zomdroid.input;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.zomdroid.R;

import java.util.Locale;
import java.util.function.Function;

/**
 * Readable, translated names for everything the controls editor lists. The enums remain the
 * stored values (layouts are saved by enum name); only what the player reads changes. Keys,
 * symbols and gamepad button names stay as printed on the hardware in every language.
 */
public final class ControlLabels {
    private ControlLabels() {}

    /** Keeps the enum constants as items, so selection and saving are unchanged, but shows labels. */
    public static final class Adapter<T> extends ArrayAdapter<T> {
        private final Function<T, String> label;

        public Adapter(@NonNull Context context, int layout, @NonNull T[] items,
                       @NonNull Function<T, String> label) {
            super(context, layout, items);
            this.label = label;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            return labelled(super.getView(position, convertView, parent), position);
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            return labelled(super.getDropDownView(position, convertView, parent), position);
        }

        private View labelled(View view, int position) {
            T item = getItem(position);
            if (item != null && view instanceof TextView) ((TextView) view).setText(label.apply(item));
            return view;
        }
    }

    public static String type(Context c, AbstractControlElement.Type type) {
        switch (type) {
            case STICK: return c.getString(R.string.ctl_type_stick);
            case STICK_WASD: return c.getString(R.string.ctl_type_stick_wasd);
            case STICK_MOUSE: return c.getString(R.string.ctl_type_stick_mouse);
            case DPAD: return c.getString(R.string.ctl_type_dpad);
            case DPAD_UP: return c.getString(R.string.ctl_pad_dpad, "↑");
            case DPAD_RIGHT: return c.getString(R.string.ctl_pad_dpad, "→");
            case DPAD_DOWN: return c.getString(R.string.ctl_pad_dpad, "↓");
            case DPAD_LEFT: return c.getString(R.string.ctl_pad_dpad, "←");
            case BUTTON_RECT: return c.getString(R.string.ctl_type_button_rect);
            case BUTTON_CIRCLE: return c.getString(R.string.ctl_type_button_circle);
            case TOUCHPAD: return c.getString(R.string.ctl_type_touchpad);
            case SCROLL_BAR: return c.getString(R.string.ctl_type_scroll_bar);
            case RADIAL_MENU: return c.getString(R.string.ctl_type_radial_menu);
            default: return type.name();
        }
    }

    public static String inputType(Context c, AbstractControlElement.InputType type) {
        return c.getString(type == AbstractControlElement.InputType.GAMEPAD
                ? R.string.ctl_input_gamepad : R.string.ctl_input_mnk);
    }

    public static String icon(Context c, ControlElementDescription.Icon icon) {
        switch (icon) {
            case GAMEPAD_BACK_ICON: return "Back";
            case GAMEPAD_START_ICON: return "Start";
            default: return c.getString(R.string.ctl_icon_none);
        }
    }

    public static String style(Context c, ControlElementDescription.Style style) {
        switch (style) {
            case FILLED: return c.getString(R.string.ctl_style_filled);
            case GLASS: return c.getString(R.string.ctl_style_glass);
            default: return c.getString(R.string.ctl_style_outline);
        }
    }

    public static String binding(Context c, GLFWBinding b) {
        switch (b) {
            case KEY_SPACE: return c.getString(R.string.ctl_key_space);
            case KEY_APOSTROPHE: return "'";
            case KEY_COMMA: return ",";
            case KEY_MINUS: return "-";
            case KEY_PERIOD: return ".";
            case KEY_SLASH: return "/";
            case KEY_SEMICOLON: return ";";
            case KEY_EQUAL: return "=";
            case KEY_LEFT_BRACKET: return "[";
            case KEY_BACKSLASH: return "\\";
            case KEY_RIGHT_BRACKET: return "]";
            case KEY_GRAVE_ACCENT: return "`";
            case KEY_WORLD_1: return "World 1";
            case KEY_WORLD_2: return "World 2";
            case KEY_PAGE_UP: return "Page Up";
            case KEY_PAGE_DOWN: return "Page Down";
            case KEY_HOME: return "Home";
            case KEY_END: return "End";
            case KEY_UP: return "↑";
            case KEY_DOWN: return "↓";
            case KEY_LEFT: return "←";
            case KEY_RIGHT: return "→";
            case KEY_ESCAPE: return "Esc";
            case KEY_ENTER: return "Enter";
            case KEY_TAB: return "Tab";
            case KEY_BACKSPACE: return "Backspace";
            case KEY_INSERT: return "Insert";
            case KEY_DELETE: return "Delete";
            case KEY_CAPS_LOCK: return "Caps Lock";
            case KEY_SCROLL_LOCK: return "Scroll Lock";
            case KEY_NUM_LOCK: return "Num Lock";
            case KEY_PRINT_SCREEN: return "Print Screen";
            case KEY_PAUSE: return "Pause";
            case KEY_LEFT_SHIFT: return c.getString(R.string.ctl_key_left, "Shift");
            case KEY_RIGHT_SHIFT: return c.getString(R.string.ctl_key_right, "Shift");
            case KEY_LEFT_CONTROL: return c.getString(R.string.ctl_key_left, "Ctrl");
            case KEY_RIGHT_CONTROL: return c.getString(R.string.ctl_key_right, "Ctrl");
            case KEY_LEFT_ALT: return c.getString(R.string.ctl_key_left, "Alt");
            case KEY_RIGHT_ALT: return c.getString(R.string.ctl_key_right, "Alt");
            case KEY_LEFT_SUPER: return c.getString(R.string.ctl_key_left, "Win");
            case KEY_RIGHT_SUPER: return c.getString(R.string.ctl_key_right, "Win");
            case KEY_KP_ENTER: return "Num Enter";
            case KEY_KP_ADD: return "Num +";
            case KEY_KP_SUBTRACT: return "Num -";
            case KEY_KP_MULTIPLY: return "Num *";
            case KEY_KP_DIVIDE: return "Num /";
            case KEY_KP_DECIMAL: return "Num .";
            case KEY_KP_EQUAL: return "Num =";
            case KEYCODE_MOVE_END: return "Move End";
            case MOUSE_BUTTON_LEFT: return c.getString(R.string.ctl_mouse_left);
            case MOUSE_BUTTON_RIGHT: return c.getString(R.string.ctl_mouse_right);
            case MOUSE_BUTTON_WHEEL: return c.getString(R.string.ctl_mouse_middle);
            case MOUSE_WHEEL_UP: return c.getString(R.string.ctl_mouse_wheel_up);
            case MOUSE_WHEEL_DOWN: return c.getString(R.string.ctl_mouse_wheel_down);
            case UI_TOGGLE_OVERLAY: return c.getString(R.string.ctl_ui_toggle_overlay);
            case UI_TOGGLE_KEYBOARD: return c.getString(R.string.ctl_ui_toggle_keyboard);
            case GAMEPAD_BUTTON_LSTICK: return c.getString(R.string.ctl_pad_left_stick_press);
            case GAMEPAD_BUTTON_RSTICK: return c.getString(R.string.ctl_pad_right_stick_press);
            case GAMEPAD_LTRIGGER: return "LT";
            case GAMEPAD_RTRIGGER: return "RT";
            case GAMEPAD_AXIS_LX: return c.getString(R.string.ctl_pad_left_stick) + " X";
            case GAMEPAD_AXIS_LY: return c.getString(R.string.ctl_pad_left_stick) + " Y";
            case GAMEPAD_AXIS_RX: return c.getString(R.string.ctl_pad_right_stick) + " X";
            case GAMEPAD_AXIS_RY: return c.getString(R.string.ctl_pad_right_stick) + " Y";
            case GAMEPAD_AXIS_LT: return c.getString(R.string.ctl_pad_axis, "LT");
            case GAMEPAD_AXIS_RT: return c.getString(R.string.ctl_pad_axis, "RT");
            case GAMEPAD_DPAD_UP: return c.getString(R.string.ctl_pad_dpad, "↑");
            case GAMEPAD_DPAD_RIGHT: return c.getString(R.string.ctl_pad_dpad, "→");
            case GAMEPAD_DPAD_DOWN: return c.getString(R.string.ctl_pad_dpad, "↓");
            case GAMEPAD_DPAD_LEFT: return c.getString(R.string.ctl_pad_dpad, "←");
            case LEFT_JOYSTICK: return c.getString(R.string.ctl_pad_left_stick);
            case RIGHT_JOYSTICK: return c.getString(R.string.ctl_pad_right_stick);
            default: break;
        }
        String n = b.name();
        // Mouse buttons 4 to 8.
        if (n.startsWith("MOUSE_BUTTON_")) return c.getString(R.string.ctl_mouse_button, n.substring(13));
        // A, B, X, Y, LB, RB as printed; BACK, START, GUIDE as Back, Start, Guide.
        if (n.startsWith("GAMEPAD_BUTTON_")) {
            String s = n.substring(15);
            return s.length() <= 2 ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
        }
        // Keypad digits: Num 0 to Num 9.
        if (n.startsWith("KEY_KP_")) return "Num " + n.substring(7);
        // Letters, digits and F1 to F12 as printed on the key.
        if (n.startsWith("KEY_")) return n.substring(4);
        return n;
    }
}
