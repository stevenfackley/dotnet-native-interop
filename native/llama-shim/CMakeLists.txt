cmake_minimum_required(VERSION 3.21)
project(dni_llama CXX C)
set(CMAKE_CXX_STANDARD 17)

# LLAMA_CPP_DIR is the path to a checked-out llama.cpp (pinned tag); built static, CPU-only.
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_CPP_DIR} llama_build)

add_library(dni_llama STATIC dni_llama.cpp)
target_include_directories(dni_llama PRIVATE ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR}/ggml/include)
target_link_libraries(dni_llama PRIVATE llama ggml)
