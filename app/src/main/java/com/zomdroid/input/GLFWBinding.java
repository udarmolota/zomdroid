package com.zomdroid.input;
import java.util.Arrays;

public enum GLFWBinding {
    /* printable keys */
    KEY_SPACE(32),
    KEY_APOSTROPHE(39),
    KEY_COMMA(44),
    KEY_MINUS(45),
    KEY_PERIOD(46),
    KEY_SLASH(47),
    KEY_0(48),
    KEY_1(49),
    KEY_2(50),
    KEY_3(51),
    KEY_4(52),
    KEY_5(53),
    KEY_6(54),
    KEY_7(55),
    KEY_8(56),
    KEY_9(57),
    KEY_SEMICOLON(59),
    KEY_EQUAL(61),
    KEY_A(65),
    KEY_B(66),
    KEY_C(67),
    KEY_D(68),
    KEY_E(69),
    KEY_F(70),
    KEY_G(71),
    KEY_H(72),
    KEY_I(73),
    KEY_J(74),
    KEY_K(75),
    KEY_L(76),
    KEY_M(77),
    KEY_N(78),
    KEY_O(79),
    KEY_P(80),
    KEY_Q(81),
    KEY_R(82),
    KEY_S(83),
    KEY_T(84),
    KEY_U(85),
    KEY_V(86),
    KEY_W(87),
    KEY_X(88),
    KEY_Y(89),
    KEY_Z(90),
    KEY_LEFT_BRACKET(91),
    KEY_BACKSLASH(92),
    KEY_RIGHT_BRACKET(93),
    KEY_GRAVE_ACCENT(96),
    KEY_WORLD_1(161),
    KEY_WORLD_2(162),
    KEY_PAGE_UP(266),
    KEY_PAGE_DOWN(267),
    KEY_HOME(268),
    KEY_END(269),
    KEY_UP(265),
    KEY_DOWN(264),
    KEY_LEFT(263),
    KEY_RIGHT(262),
    KEY_F1(290),
    KEY_F2(291),
    KEY_F3(292),
    KEY_F4(293),
    KEY_F5(294),
    KEY_F6(295),
    KEY_F7(296),
    KEY_F8(297),
    KEY_F9(298),
    KEY_F10(299),
    KEY_F11(300),
    KEY_F12(301),

    /* Additional kb keys */
    KEY_ESCAPE(256),
    KEY_ENTER(257),
    KEY_TAB(258),
    KEY_BACKSPACE(259),
    KEY_INSERT(260),
    KEY_DELETE(261),
    KEY_CAPS_LOCK(280),
    KEY_SCROLL_LOCK(281),
    KEY_NUM_LOCK(282),
    KEY_PRINT_SCREEN(283),
    KEY_PAUSE(284),
    KEY_LEFT_SHIFT(340),
    KEY_RIGHT_SHIFT(344),
    KEY_LEFT_CONTROL(341),
    KEY_RIGHT_CONTROL(345),
    KEY_LEFT_ALT(342),
    KEY_RIGHT_ALT(346),
    KEY_LEFT_SUPER(343),
    KEY_RIGHT_SUPER(347),

    KEY_KP_0(320),
    KEY_KP_1(321),
    KEY_KP_2(322),
    KEY_KP_3(323),
    KEY_KP_4(324),
    KEY_KP_5(325),
    KEY_KP_6(326),
    KEY_KP_7(327),
    KEY_KP_8(328),
    KEY_KP_9(329),
    KEY_KP_ENTER(335),
    KEY_KP_ADD(334),
    KEY_KP_SUBTRACT(333),
    KEY_KP_MULTIPLY(332),
    KEY_KP_DIVIDE(331),
    KEY_KP_DECIMAL(330),
    KEY_KP_EQUAL(336),
    KEYCODE_MOVE_END(123),

    /* mouse buttons */
    MOUSE_BUTTON_LEFT(0),
    MOUSE_BUTTON_RIGHT(1),
    MOUSE_BUTTON_WHEEL(2),
    MOUSE_BUTTON_4(3),
    MOUSE_BUTTON_5(4),
    MOUSE_BUTTON_6(5),
    MOUSE_BUTTON_7(6),
    MOUSE_BUTTON_8(7),
    UI_TOGGLE_OVERLAY(-100),

    /* gamepad buttons*/
    GAMEPAD_BUTTON_A(0),
    GAMEPAD_BUTTON_B(1),
    GAMEPAD_BUTTON_X(2),
    GAMEPAD_BUTTON_Y(3),
    GAMEPAD_BUTTON_LB(4),
    GAMEPAD_BUTTON_RB(5),
    GAMEPAD_BUTTON_BACK(6),
    GAMEPAD_BUTTON_START(7),
    GAMEPAD_BUTTON_GUIDE(8),
    GAMEPAD_BUTTON_LSTICK(9),
    GAMEPAD_BUTTON_RSTICK(10),

    /* special button bindings for triggers, since technically they are axes */
    GAMEPAD_LTRIGGER(-1),
    GAMEPAD_RTRIGGER(-1),

    /* gamepad axes */
    GAMEPAD_AXIS_LX(0),
    GAMEPAD_AXIS_LY(1),
    GAMEPAD_AXIS_RX(2),
    GAMEPAD_AXIS_RY(3),
    GAMEPAD_AXIS_LT(4),
    GAMEPAD_AXIS_RT(5),

    /* DPAD as pseudo-buttons (handled via sendJoystickDpad) */
    GAMEPAD_DPAD_UP(-2),
    GAMEPAD_DPAD_RIGHT(-3),
    GAMEPAD_DPAD_DOWN(-4),
    GAMEPAD_DPAD_LEFT(-5),

    /* special binding for joysticks */
    LEFT_JOYSTICK(-1),
    RIGHT_JOYSTICK(-1);

    public final int code;
    static final int MNK_MIN_ORDINAL = KEY_SPACE.ordinal();
    static final int MNK_MAX_ORDINAL = MOUSE_BUTTON_8.ordinal();
    static final int GAMEPAD_MIN_ORDINAL = GAMEPAD_BUTTON_A.ordinal();
    static final int GAMEPAD_MAX_ORDINAL = GAMEPAD_DPAD_LEFT.ordinal(); //GAMEPAD_RTRIGGER.ordinal();

    GLFWBinding(int code) {
        this.code = code;
    }

    public static GLFWBinding[] valuesForType(AbstractControlElement.InputType type) {
        GLFWBinding[] result = new GLFWBinding[0];
        GLFWBinding[] values = GLFWBinding.values();
        GLFWBinding[] all = GLFWBinding.values();
        
        int bindingCount;
        if (type == AbstractControlElement.InputType.MNK) {
            return Arrays.stream(all)
                    .filter(b -> {
                    String name = b.name();
                    return
                        (b.ordinal() >= KEY_SPACE.ordinal() && b.ordinal() <= KEY_KP_EQUAL.ordinal()) // keyboard
                        || (b.ordinal() >= MOUSE_BUTTON_LEFT.ordinal() && b.ordinal() <= MOUSE_BUTTON_8.ordinal()) // mouse
                        || name.startsWith("MOUSE_WHEEL_") // mouse wheel
                        || b == UI_TOGGLE_OVERLAY;
                })
                .toArray(GLFWBinding[]::new);

        } 
        
        if (type == AbstractControlElement.InputType.GAMEPAD) {
            return Arrays.stream(all)
                    .filter(b -> b.ordinal() >= GAMEPAD_BUTTON_A.ordinal()
                              && b.ordinal() <= GAMEPAD_DPAD_LEFT.ordinal())
                    .toArray(GLFWBinding[]::new);
        }
        return new GLFWBinding[0];
    }
}
