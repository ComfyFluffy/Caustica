cmake_minimum_required(VERSION 3.20)
project(ngxshim LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT DEFINED ENV{DLSS_SDK})
  message(FATAL_ERROR "DLSS_SDK environment variable is not set")
endif()

if(NOT DEFINED ENV{VULKAN_SDK})
  message(FATAL_ERROR "VULKAN_SDK environment variable is not set")
endif()

add_library(ngxshim SHARED ngx_shim.cpp)

set(DLSS_SDK_DIR "$ENV{DLSS_SDK}")
set(DLSS_SDK_INCLUDE_DIR "${DLSS_SDK_DIR}/include")


target_include_directories(ngxshim PRIVATE "${DLSS_SDK_INCLUDE_DIR}")
target_include_directories(ngxshim PRIVATE "$ENV{VULKAN_SDK}/include")
if(WIN32)
  set(NGX_STATIC_LIB "${DLSS_SDK_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_d.lib")
  set_property(TARGET ngxshim PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")
else()
  set(NGX_STATIC_LIB "${DLSS_SDK_DIR}/lib/Linux_x86_64/libnvsdk_ngx.a")
endif()
target_link_libraries(ngxshim PRIVATE "${NGX_STATIC_LIB}" ${CMAKE_DL_LIBS})

set_target_properties(ngxshim PROPERTIES
    OUTPUT_NAME "ngxshim"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/out"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/out"
    ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/out")
