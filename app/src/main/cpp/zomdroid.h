#ifndef ZOMDROID_ZOMDROID_H
#define ZOMDROID_ZOMDROID_H

#include "android/native_window.h"

void zomdroid_set_art_vm(void* vm);

void zomdroid_start_game(const char* game_dir_path, const char* library_dir_path, int jvm_argc,
                         const char** jvm_argv, const char* main_class_name, int argc, const char** argv);

void zomdroid_deinit();
int zomdroid_init();

void zomdroid_surface_deinit();
void zomdroid_surface_init(ANativeWindow* wnd, int width, int height);

void zomdroid_event_keyboard(int key, bool is_pressed);
void zomdroid_event_mouse_button(int button, bool is_pressed);
void zomdroid_event_cursor_pos(double x, double y);
void zomdroid_event_joystick_axis(int axis, float state);
void zomdroid_event_joystick_dpad(int dpad, char state);
void zomdroid_event_joystick_button(int button, bool is_pressed);
void zomdroid_event_joystick_connected();
void zomdroid_event_char(unsigned int codepoint);

#endif //ZOMDROID_ZOMDROID_H
