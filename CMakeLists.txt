cmake_minimum_required(VERSION 3.22.1)
project(audiophile_vocal_remover)

add_library(audiophile_vocal_remover SHARED native_vocal_remover.cpp)

target_compile_features(audiophile_vocal_remover PRIVATE cxx_std_17)
target_compile_options(audiophile_vocal_remover PRIVATE
    -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Wshadow
)

target_include_directories(audiophile_vocal_remover PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
)

# PremiumVocalRemoverDSP.h and ProfessionalStereoWidenerDSP_v10.h must be
# copied into this directory (or supplied through target_include_directories).
