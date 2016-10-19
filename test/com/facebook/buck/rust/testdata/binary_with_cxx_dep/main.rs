#[cfg(feature = "static")]
extern crate adder_static as adder;
#[cfg(feature = "shared")]
extern crate adder_shared as adder;

fn main() {
    let a = 10;
    let b = 15;
    let sum = adder::add(a, b);

    println!("{} + {} = {}", a, b, sum);
}