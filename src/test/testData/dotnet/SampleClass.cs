using System;

namespace TestProject
{
    public interface IAnimal
    {
        string Name { get; }
        void Speak();
    }

    public abstract class Animal : IAnimal
    {
        public abstract string Name { get; }
        public abstract void Speak();

        public virtual void Sleep()
        {
            Console.WriteLine($"{Name} is sleeping");
        }
    }

    public class Dog : Animal
    {
        public override string Name => "Dog";

        public override void Speak()
        {
            Console.WriteLine("Woof!");
        }

        public void Fetch()
        {
            Speak();
            Console.WriteLine("Fetching!");
        }
    }

    public class Cat : Animal
    {
        public override string Name => "Cat";

        public override void Speak()
        {
            Console.WriteLine("Meow!");
        }
    }

    public struct Point
    {
        public int X { get; set; }
        public int Y { get; set; }
    }

    public enum Color
    {
        Red,
        Green,
        Blue
    }
}
