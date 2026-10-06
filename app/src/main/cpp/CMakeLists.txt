cmake_minimum_required(VERSION 3.22.1)
project(audiophile_player LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_library(peq SHARED
    peq_engine.cpp
    peq_jni.cpp
)

target_compile_features(peq PRIVATE cxx_std_17)
target_compile_options(peq PRIVATE -O3 -Wall -Wextra -Wpedantic -fno-fast-math)
target_link_options(peq PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(peq PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(peq PRIVATE ${log-lib})


add_library(widener SHARED
    widener_jni.cpp
)

target_compile_features(widener PRIVATE cxx_std_17)
target_compile_options(widener PRIVATE -O3 -Wall -Wextra -Wpedantic -fno-fast-math)
target_link_options(widener PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(widener PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(widener PRIVATE ${log-lib})


add_library(audiophile_vocal_remover SHARED
    native_vocal_remover.cpp
)

target_compile_features(audiophile_vocal_remover PRIVATE cxx_std_17)
target_compile_options(audiophile_vocal_remover PRIVATE -O3 -Wall -Wextra -Wpedantic -fno-fast-math)
target_link_options(audiophile_vocal_remover PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(audiophile_vocal_remover PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(audiophile_vocal_remover PRIVATE ${log-lib})
