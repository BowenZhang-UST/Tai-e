/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.ir.exp;

import pascal.taie.World;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;

import static pascal.taie.language.classes.StringReps.CLASS;

public class ClassLiteral implements ReferenceLiteral {

    /**
     * The type represented by this class object.
     */
    private final Type value;

    private ClassLiteral(Type value) {
        this.value = value;
    }

    public static ClassLiteral get(Type value) {
        return new ClassLiteral(value);
    }

    @Override
    public ClassType getType() {
        // TODO: cache Class type in a static field? Doing so
        //  requires to reset the field when resetting World.
        return World.getTypeManager().getClassType(CLASS);
    }

    public Type getTypeValue() {
        return value;
    }

    @Override
    public <T> T accept(ExpVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ClassLiteral that = (ClassLiteral) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.getName() + ".class";
    }
}
