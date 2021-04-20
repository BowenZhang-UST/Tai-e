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

package pascal.taie.analysis.oldpta.ir;

import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.util.HashUtils;

public class DefaultVariable extends AbstractVariable {

    private final String name;

    public DefaultVariable(String name, Type type, JMethod container) {
        super(type, container);
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DefaultVariable that = (DefaultVariable) o;
        return container.equals(that.container) &&
                name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return HashUtils.hash(container, name);
    }

    @Override
    public String toString() {
        return container + "/" + name;
    }
}
