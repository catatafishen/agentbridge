cmake_minimum_required(VERSION 3.20)
project(agentbridge_fixture LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# generated/ is populated on demand by the find_references large-graph bench (see LargeGraphFixture)
file(GLOB GENERATED_SOURCES CONFIGURE_DEPENDS generated/*.cpp)

add_executable(agentbridge_fixture main.cpp shelf.cpp ${GENERATED_SOURCES})
