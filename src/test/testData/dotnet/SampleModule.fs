namespace TestProject

type IShape =
    abstract member Area : float
    abstract member Perimeter : float

type Circle(radius: float) =
    interface IShape with
        member _.Area = System.Math.PI * radius * radius
        member _.Perimeter = 2.0 * System.Math.PI * radius

    member _.Radius = radius

type Rectangle(width: float, height: float) =
    interface IShape with
        member _.Area = width * height
        member _.Perimeter = 2.0 * (width + height)

    member _.Width = width
    member _.Height = height

module Geometry =
    let circleArea (r: float) = System.Math.PI * r * r
    let rectangleArea (w: float) (h: float) = w * h
