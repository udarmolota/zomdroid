package com.zomdroid.input;

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

    /* Additional kb keys */
    KEY_ESCAPE(27),
    KEY_ENTER(13),
    KEY_TAB(9),
    KEY_BACKSPACE(8),

    /* mouse buttons */
    MOUSE_BUTTON_LEFT(0),
    MOUSE_BUTTON_RIGHT(1),
    MOUSE_BUTTON_WHEEL(2),
    MOUSE_BUTTON_4(3),
    MOUSE_BUTTON_5(4),
    MOUSE_BUTTON_6(5),
    MOUSE_BUTTON_7(6),
    MOUSE_BUTTON_8(7),

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

    /* special binding for joysticks */
    LEFT_JOYSTICK(-1),
    RIGHT_JOYSTICK(-1);

    public final int code;
    static final int MNK_MIN_ORDINAL = KEY_SPACE.ordinal();
    static final int MNK_MAX_ORDINAL = MOUSE_BUTTON_8.ordinal();
    static final int GAMEPAD_MIN_ORDINAL = GAMEPAD_BUTTON_A.ordinal();
    static final int GAMEPAD_MAX_ORDINAL = GAMEPAD_RTRIGGER.ordinal();

    GLFWBinding(int code) {
        this.code = code;
    }

    public static GLFWBinding[] valuesForType(AbstractControlElement.InputType type) {
        GLFWBinding[] result = new GLFWBinding[0];
        GLFWBinding[] values = GLFWBinding.values();
        int bindingCount;
        if (type == AbstractControlElement.InputType.MNK) {
            bindingCount = MNK_MAX_ORDINAL - MNK_MIN_ORDINAL + 1;
            result = new GLFWBinding[bindingCount];
            int i = 0;
            for (int n = MNK_MIN_ORDINAL; n <= MNK_MAX_ORDINAL; n++) {
                result[i++] = values[n];
            }
        } else if (type == AbstractControlElement.InputType.GAMEPAD) {
            bindingCount = GAMEPAD_MAX_ORDINAL - GAMEPAD_MIN_ORDINAL + 1;
            result = new GLFWBinding[bindingCount];
            int i = 0;
            for (int n = GAMEPAD_MIN_ORDINAL; n <= GAMEPAD_MAX_ORDINAL; n++) {
                result[i++] = values[n];
            }
        }
        return result;
    }
}
