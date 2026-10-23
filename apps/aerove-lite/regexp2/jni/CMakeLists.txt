cmake_minimum_required(VERSION 3.22)
project(aerove_filter_jni C)
add_library(aerove_filter_jni SHARED filter_bridge.c)
target_link_libraries(aerove_filter_jni dl)
