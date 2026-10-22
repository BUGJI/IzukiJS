cmake_minimum_required(VERSION 3.22.1)

project(izuki_qjs_shim CXX)

add_library(izuki_qjs_shim SHARED izuki_quickjs_shim.cpp)

set_target_properties(
    izuki_qjs_shim
    PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

target_link_libraries(izuki_qjs_shim dl log)
