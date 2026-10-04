cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

# OpenXR (Meta Quest VR mode) removed together with XrActivity. Its JNI symbols
# were named Java_com_winlator_XrActivity_* while the class lived in
# com.winlator.cmod, so they never bound and VR mode could not have worked.
# OpenXR(메타 퀘스트 VR 모드)를 XrActivity와 함께 제거함. JNI 심볼 이름이
# Java_com_winlator_XrActivity_* 였는데 클래스는 com.winlator.cmod에 있어서
# 연결된 적이 없고, VR 모드는 동작할 수 없는 상태였음

# PatchELF (GPL-3.0) removed: no Java code called it, the JNI wrapper was only
# empty TODO stubs, yet libpatchelf.so was still built and packed into the APK
# PatchELF(GPL-3.0) 제거함: 호출하는 Java 코드가 없었고 JNI 래퍼도 빈 TODO
# 껍데기뿐이었는데, libpatchelf.so는 계속 빌드되어 APK에 들어가고 있었음

# Add Winlator shared library
add_library(winlator SHARED
        winlator/drawable.c
        winlator/gpu_image.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
        winlator/vulkan.cpp
)

target_link_libraries(winlator
        log
        android
        jnigraphics
        aaudio
        EGL
        GLESv2
        GLESv3
)


# ---- EVSHIM (phase-0 stub) -----------------------------------
add_library(evshim SHARED winlator/evshim.c)

# ---------- SDL2 library (Build from source) ----------
# Add SDL2 as a library target.
# This assumes cpp/sdl2 has its own CMakeLists.txt or you list its sources here.
# Most commonly, SDL2 comes with its own CMake build system.
# If cpp/sdl2 has a CMakeLists.txt:
add_subdirectory(sdl2) # This will create an SDL2 target (often named SDL2::SDL2 or just SDL2)

# If you need to list SDL2 source files manually (less ideal if SDL2 is complex):
# file(GLOB SDL2_SOURCES "sdl2/*.c" "sdl2/src/*/*.c" ...) # Adjust glob patterns
# add_library(MySDL2 STATIC ${SDL2_SOURCES}) # Or SHARED
# target_include_directories(MySDL2 PUBLIC "${CMAKE_CURRENT_LIST_DIR}/sdl2/include") # Assuming headers are in sdl2/include


# Tell evshim's compiler where to find SDL2 headers (still needed)
target_include_directories(evshim PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/sdl2/include" # Adjust if SDL headers are elsewhere in your sdl2 dir
)

# ---------- link everything ----------
target_link_libraries(evshim PRIVATE # Or PUBLIC
        SDL2            # If add_subdirectory(sdl2) created a target named SDL2
        # Or MySDL2     # If you used add_library(MySDL2 ...)
        # Or SDL2::SDL2 # Common imported target name for modern CMake SDL2
        log
        dl
)

# drop .so into jniLibs/<abi> so Gradle packs it automatically
set_target_properties(evshim PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY
        "${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}")
