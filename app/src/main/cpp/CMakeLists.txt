cmake_minimum_required(VERSION 3.4.1)
project(zomdroid)
cmake_policy(SET CMP0079 NEW)
set(CMAKE_C_STANDARD 11)

#add_subdirectory(gl4es)
#set_target_properties(GL PROPERTIES
#        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY} # originally ${CMAKE_SOURCE_DIR}/lib
#        SUFFIX .so # originally so.1
#        #OUTPUT_NAME gl4es
#)
##target_compile_definitions(GL PRIVATE DEBUG)
#target_compile_definitions(GL PRIVATE NOX11)
#target_compile_definitions(GL PRIVATE NO_GBM)
#target_compile_definitions(GL PRIVATE NOEGL) # EGL context creation is handled by glfw

set(GLFW_BUILD_WAYLAND OFF CACHE BOOL "Disable Wayland support" FORCE)
set(GLFW_BUILD_X11 OFF CACHE BOOL "Disable X11 support" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared libs" FORCE)
set(GLFW_BUILD_DOCS OFF CACHE BOOL "Build docs" FORCE)
set(GLFW_INSTALL OFF CACHE BOOL "Install" FORCE)
set(GLFW_BUILD_ZOMDROID ON)
add_subdirectory(glfw)
set_target_properties(glfw PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY} # originally ${CMAKE_SOURCE_DIR}/lib
)
target_include_directories(glfw PRIVATE .)
target_link_libraries(glfw PRIVATE log zomdroid EGL nativewindow)

add_subdirectory(liblinkernsbypass)

add_library(zomdroid SHARED zomdroid.c zomdroid_jni.c)
target_link_libraries(zomdroid log android linkernsbypass)

add_library(zomdroidlinker SHARED linker.c emulation.c wrapped_jni.c macho_loader.c macho_bridge.c macho_string.cpp macho_unwind.c macho_jnienv.c macho_pathfind.c)

# The C++ exception runtime the macOS dylibs are bound to: our own libc++abi + libunwind, whole,
# with the dynamic-FDE registration the loader needs (see macho_eh.c). Kept out of zomdroidlinker
# so it can never shadow libc++_shared's runtime for anyone else; only zomdroid_macho_eh_* is
# exported. The archive paths come from the toolchain itself.
execute_process(COMMAND ${CMAKE_C_COMPILER} --target=${CMAKE_C_COMPILER_TARGET} -print-file-name=libunwind.a
                OUTPUT_VARIABLE MACHO_EH_LIBUNWIND OUTPUT_STRIP_TRAILING_WHITESPACE)
execute_process(COMMAND ${CMAKE_C_COMPILER} --target=${CMAKE_C_COMPILER_TARGET} -print-file-name=libc++abi.a
                OUTPUT_VARIABLE MACHO_EH_LIBCXXABI OUTPUT_STRIP_TRAILING_WHITESPACE)
add_library(zomdroid_macho_eh SHARED macho_eh.c)
if(NOT EXISTS "${MACHO_EH_LIBCXXABI}" OR NOT EXISTS "${MACHO_EH_LIBUNWIND}")
    message(FATAL_ERROR "Mach-O exception runtime archives not found in the selected NDK")
endif()
add_dependencies(zomdroidlinker zomdroid_macho_eh)
target_link_libraries(zomdroid_macho_eh PRIVATE c++_shared log)
target_link_options(zomdroid_macho_eh PRIVATE
    "-Wl,--whole-archive" "${MACHO_EH_LIBCXXABI}" "${MACHO_EH_LIBUNWIND}" "-Wl,--no-whole-archive"
    "-Wl,--exclude-libs,ALL")
target_compile_features(zomdroidlinker PRIVATE cxx_std_17)
target_link_libraries(zomdroidlinker PRIVATE c log box64 zomdroid m) # link to libc before box64 to prevent mmap override
target_link_options(zomdroidlinker PRIVATE "-Wl,-z,global")

set(NOGIT ON)
set(ARM_DYNAREC ON)
add_subdirectory(box64)
target_compile_definitions(box64 PRIVATE BUILD_DYNAMIC)
