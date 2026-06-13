#include "classdef.h"

namespace vsc {

int Widget::area() const {
    return width * height;
}

} // namespace vsc

int main() {
    vsc::Widget w;
    w.width = 4;
    w.height = 3;
    return w.area();
}
