cmake_minimum_required(VERSION 3.20)
project(ngxshim LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
  set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS Debug Release RelWithDebInfo MinSizeRel)
endif()

get_filename_component(NGX_SHIM_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(NGX_SHIM_OUTPUT_ROOT "${NGX_SHIM_REPO_ROOT}/build/native/ngx_shim"
    CACHE PATH "Directory for built NGX shim artifacts")
set(NGX_SHIM_OUTPUT_DIR "${NGX_SHIM_OUTPUT_ROOT}/$<LOWER_CASE:$<CONFIG>>")

function(ngxshim_set_artifact_dirs target)
  set_target_properties(${target} PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY "${NGX_SHIM_OUTPUT_DIR}"
      LIBRARY_OUTPUT_DIRECTORY "${NGX_SHIM_OUTPUT_DIR}"
      ARCHIVE_OUTPUT_DIRECTORY "${NGX_SHIM_OUTPUT_DIR}")
endfunction()

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
    OUTPUT_NAME "ngxshim")
ngxshim_set_artifact_dirs(ngxshim)
