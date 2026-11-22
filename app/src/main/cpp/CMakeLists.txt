cmake_minimum_required(VERSION 3.22.1)

project("morphe" C)
set_property(GLOBAL PROPERTY JOB_POOLS extended_compile=2 extended_link=1)
set(CMAKE_JOB_POOL_COMPILE extended_compile)
set(CMAKE_JOB_POOL_LINK extended_link)

# Starts the JVM that patches as a process of the patcher service
add_library(jvm_launcher SHARED
        jvm_launcher.c)
