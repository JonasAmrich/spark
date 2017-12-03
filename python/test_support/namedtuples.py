from collections import namedtuple


Point = namedtuple("Point", "x y")

class PointSubclass(Point):
    def sum(self):
        return self.x + self.y
