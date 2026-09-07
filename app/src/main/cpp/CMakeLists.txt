cmake_minimum_required(VERSION 3.22.1)
project("wasti_ai_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    wasti_ai_native
    SHARED
    wasti_ai_native.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    wasti_ai_native
    ${log-lib}
    m
)
