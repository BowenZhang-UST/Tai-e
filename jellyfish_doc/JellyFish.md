# JellyFish

jellyfish is a transpiler from Tai-e IR to LLVM IR, for LLVM-based static analyzers.

## Quick start

### 0. Install JDK-17 required by Tai-e.

Then set environment variables.

```shell
export JAVA_HOME="/your/java/home"
export PATH="$JAVA_HOME/path/to/bin:$PATH"
```

**NOTE: all the later steps requires these two variables.**

### 1. Build JellyFish as a component of Tai-e

- Development build (much quicker).

    ```
    ./gradlew compileJava
    ```

- Production build (takes minutes). It generates an all-in-one jar application in `build` dir. It is
  the complete Tai-e jar file.

    ```shell
    ./gradlew fatJar
    ```

### 2.Translate test cases

Compile the test cases.

```shell
javac tests/class/*.java
```

Run JellyFish as a pass in Tai-e.

- For development build:
    ```shell
    ./gradlew run --args="-a jelly-fish -cp tests/class -m Class1"
    ```
- For production build:
    ```shell
    java -jar build/[tai-e far file] -a jelly-fish -cp tests/class -m Class1
    ```

The last command line would generate an LLVM 12 bitcode `out.bc` at current dir. You can
use `llvm-dis` to get the readable ir file.

```shell
llvm-dis out.bc
```

## TODOs

We need to perform some optimizations and analyses to improve the quality of the generated LLVM IR:

**Optimizations**

1. Mem2Reg: Because of the `Var` in Tai-e, we have created many redundant load-store pairs through
   the vars.
2. Simplify CFG. Currently, each Tai-e statement correponds to a block. We need to do some merge.
3. Down/up casting identification: When we perform up or down casting in Java, it's translated to
   direct bitcast.

**High-level information resolution**

1. resolve class hierarchy
2. resolve debug information
