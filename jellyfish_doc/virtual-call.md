# Translating Virtual call: design choices

## Background

Given a java class:

```java
class A {
}
```

It will be translated to an llvm struct. We will discuss what's inside the struct later.

```c
%A = type {}
```

Then, an instance of the class will be a pointer to the struct.

```c
%a = alloca %A*
```

Given a java method:

```java
class A {
    int foo(int i) {
        return i;
    }
}
```

In jellyfish, it's translated to an llvm function with explicit 'self' pointer:

```c
declare i32 foo(%A*, i32)
```

## Challenge

Given a method call that can be resolved during compile time:

```java
A a = new A();
a.

foo(1);
```

Of course, it can be translated to a direct llvm call.

Next, we first extend class `A` with two sub classes:

```java
class B extends A {
    int foo(int i) {
        return i + 1;
    }
}

class C extends A {
    int foo(int i) {
        return i + 1;
    }
}
```

The next code illustrates a virtual call that can only be dynamically resolved.

```java
//...
A a;
if(...){
a =(A)new

B();
}else{
a =(A)new

C();
}
        a.

foo(1);
```

Intuitively, we need to simulate the dynamic dispatch using a v-table-like approach.

In what follows, we use the three classes `A`, `B`, and `C` to demonstrate the two possible
approaches.

## Approach I. Multi-layered table.

The memory layout of the translated three classes:

```C
%foo_type = i32 (%A*, i32)

define i32 A.foo(%A*, i32) {...}
define i32 B.foo(%B*, i32) {...}
define i32 C.foo(%C*, i32) {...}

%A = type {
    %foo_type*
}
%B = type {
    %A*
}
%C = type {
    %A*
}
```

Next let's consider how the fields are initialized when an object of `B` is newed (similar for C).

```
%% new object
%b = @jellyfish.new(%B* null)

%% set function pointer
%a_ptr = getelementptr %B, %B* %b, i32 0, i32 0
%a = load %A*, %A** %a_ptr
%foo_ptr = getelementptr %A, %A* %a, i32 0, i32 0
store %foo_type* (bitcast (i32 (%B*, i32))* @B.foo to %foo_type*), %foo_type** %foo_ptr
```

Finally, when the object of `B` is casted to its parent class `A`, and then call the method `foo`,
it goes like:

```
%% cast B object to A object
%a_ptr = getelementptr %B, %B* %b, i32 0, i32 0
%casted_b = load %A*, %A** %a_ptr

%% call method foo
%foo_ptr = getelementptr %A, %A* %casted_b, i32 0, i32 0
%foo = load %foo_type*, %foo_type** %foo_ptr
$res = i32 @foo(%A* %casted_b, i32 1)
```

## Approach II. Single-layered table.

The memory layout of the translated three classes:

```C
%foo_type = i32 (%A*, i32)

define i32 A.foo(%A*, i32) {...}
define i32 B.foo(%B*, i32) {...}
define i32 C.foo(%C*, i32) {...}

%A = type {
    %foo_type*
}
%B = type {
    %foo_type*
}
%C = type {
    %foo_type*
}
```

Field initialization when B is newed.

```
%% new object
%b = @jellyfish.new(%B* null)

%% set function pointer
%foo_ptr = getelementptr %B, %B* %b, i32 0, i32 0
store %foo_type* (bitcast (i32 (%B*, i32))* @B.foo to %foo_type*), %foo_type** %foo_ptr
```

When the object of `B` is casted to its parent class `A`, and then call the method `foo`, it goes
like:

```
%% cast B object to A object
%a_addr = getelementptr %B, %B* %b, i32 0, i32 0
%casted_b = bitcast (%foo_type** %a_addr) to %A*

%% call method foo
%foo_ptr = getelementptr %A, %A* %casted_b, i32 0, i32 0
%foo = load %foo_type*, %foo_type** %foo_ptr
$res = i32 @foo(%A* %casted_b, i32 1)
```
