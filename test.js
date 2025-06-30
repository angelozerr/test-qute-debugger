// person.js

class Person {
  constructor(name, age) {
    this.name = name;
    this.age = age;
  }
}

// Création de plusieurs personnes
const person1 = new Person("Alice", 30);
const person2 = new Person("Bob", 25);
const person3 = new Person("Charlie", 28);

// Ajout dans une liste
const people = [person1, person2, person3];

// Affichage dans la console
console.log(people);
