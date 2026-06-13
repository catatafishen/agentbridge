#ifndef AGENTBRIDGE_FIXTURE_CLASSDEF_H
#define AGENTBRIDGE_FIXTURE_CLASSDEF_H

// Fixture symbols with stable names and line positions, used by the IDE
// integration bench to assert PSI-dependent MCP tools against the real
// CLion Nova (Radler) backend. Do not renumber casually — integration
// tests reference symbols by name; if you add file:line assertions, keep
// them in sync.

namespace vsc {

class Widget {
public:
    int width;
    int height;

    int area() const;
};

struct Point {
    int x;
    int y;
};

enum class Colour {
    Red,
    Green,
    Blue
};

} // namespace vsc

#endif // AGENTBRIDGE_FIXTURE_CLASSDEF_H
