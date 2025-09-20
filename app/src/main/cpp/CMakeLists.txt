cmake_minimum_required(VERSION 3.4.1)
project(zomdroid)

# Use modern policies for target_link_libraries on subdirectories
cmake_policy(SET CMP0079 NEW)

# We now compile a .cpp (InputNativeInterface.cpp), so set C/C++ standards
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# --- GLFW options ---
set(GLFW_BUILD_WAYLAND OFF CACHE BOOL "Disable Wayland support" FORCE)
set(GLFW_BUILD_X11 OFF   CACHE BOOL "Disable X11 support"       FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared libs"         FORCE)
set(GLFW_BUILD_DOCS OFF  CACHE BOOL "Build docs"                FORCE)
set(GLFW_INSTALL OFF     CACHE BOOL "Install"                   FORCE)
set(GLFW_BUILD_ZOMDROID ON)

# Build bundled GLFW first (needed by our JNI library later)
add_subdirectory(glfw)

# Make sure the produced libglfw.so ends up where the rest of libs go
set_target_properties(glfw PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)

# Link GLFW to Android system libs it needs.
# IMPORTANT: We deliberately DO NOT link glfw -> zomdroid to avoid circular deps.
target_link_libraries(glfw PRIVATE log EGL android nativewindow)

# Build liblinkernsbypass before we link against it from zomdroid
add_subdirectory(liblinkernsbypass)

# --- Our JNI bridge that exposes InputNativeInterface.sendJoystickDpad() ---
add_library(zomdroid SHARED
    zomdroid.c
    zomdroid_jni.c
    InputNativeInterface.cpp   # NEW: JNI that calls _glfwInputJoystickHat
)

# We include GLFW public headers and INTERNAL headers here because
# InputNativeInterface.cpp uses GLFW internals (internal.h / _glfwInputJoystickHat)
target_include_directories(zomdroid PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/glfw/include
    ${CMAKE_CURRENT_LIST_DIR}/glfw/src
)

# Link order note:
# - glfw: for _glfw* symbols resolved from GLFW
# - linkernsbypass / android / log: used by existing JNI code
target_link_libraries(zomdroid
    log
    android
    linkernsbypass
    glfw
)

# This helper .so depends on box64 and our JNI lib; keep link order as-is.
# The explicit 'c' is kept due to prior mmap override note.
add_library(zomdroidlinker SHARED
    linker.c
    emulation.c
    wrapped_jni.c
)
target_link_libraries(zomdroidlinker PRIVATE
    c
    log
    box64
    zomdroid
)
# Make symbols globally visible at runtime (matches previous behavior)
target_link_options(zomdroidlinker PRIVATE "-Wl,-z,global")

# Box64 as before
set(NOGIT ON)
set(ARM_DYNAREC ON)
add_subdirectory(box64)
target_compile_definitions(box64 PRIVATE BUILD_DYNAMIC)
