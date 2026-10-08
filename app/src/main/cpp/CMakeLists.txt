cmake_minimum_required(VERSION 3.22.1)
project(audiophile_player LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# peq / widener belong to the full Audiophile Player repo and are NOT part of
# this extracted bundle. Build them only when their sources are present so the
# bundle's CMake file does not fail on missing files.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/peq_engine.cpp" AND
   EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/peq_jni.cpp")
    add_library(peq SHARED
        peq_engine.cpp
        peq_jni.cpp
    )

    target_compile_features(peq PRIVATE cxx_std_17)
    target_compile_options(peq PRIVATE -O3 -Wall -Wextra -Wpedantic -fno-fast-math)
    target_link_options(peq PRIVATE "-Wl,-z,max-page-size=16384")
    target_include_directories(peq PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(peq PRIVATE ${log-lib})
endif()


if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/widener_jni.cpp")
    add_library(widener SHARED
        widener_jni.cpp
    )

    target_compile_features(widener PRIVATE cxx_std_17)
    target_compile_options(widener PRIVATE -O3 -Wall -Wextra -Wpedantic -fno-fast-math)
    target_link_options(widener PRIVATE "-Wl,-z,max-page-size=16384")
    target_include_directories(widener PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(widener PRIVATE ${log-lib})
endif()


# PremiumVocalRemoverDSP.h #includes this header (SmoothedValue,
# StereoTransientDetector, StereoLinkedTruePeakLimiter, clampf, dbToGain, ...).
# It lives in the full repo's app/src/main/cpp and is not in this bundle.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/ProfessionalStereoWidenerDSP_v10.h")
    message(FATAL_ERROR
        "ProfessionalStereoWidenerDSP_v10.h is missing from ${CMAKE_CURRENT_SOURCE_DIR}. "
        "Copy it from the full Audiophile Player repository (app/src/main/cpp) "
        "next to PremiumVocalRemoverDSP.h before building.")
endif()

add_library(audiophile_vocal_remover SHARED
    native_vocal_remover.cpp
)

target_compile_features(audiophile_vocal_remover PRIVATE cxx_std_17)
target_compile_options(audiophile_vocal_remover PRIVATE -O3 -Wall -Wextra -Wpedantic -fno-fast-math)
target_link_options(audiophile_vocal_remover PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(audiophile_vocal_remover PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(audiophile_vocal_remover PRIVATE ${log-lib})