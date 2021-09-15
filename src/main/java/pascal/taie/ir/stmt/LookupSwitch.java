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

package pascal.taie.ir.stmt;

import pascal.taie.ir.exp.Var;
import pascal.taie.util.collection.Pair;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class LookupSwitch extends SwitchStmt {

    private final List<Integer> caseValues;

    public LookupSwitch(Var var, List<Integer> caseValues) {
        super(var);
        this.caseValues = List.copyOf(caseValues);
    }

    public int getCaseValue(int index) {
        return caseValues.get(index);
    }

    @Override
    public List<Integer> getCaseValues() {
        return caseValues;
    }

    @Override
    public List<Pair<Integer, Stmt>> getCaseTargets() {
        return IntStream.range(0, caseValues.size())
                .mapToObj(i -> new Pair<>(caseValues.get(i),
                        targets == null ? null : targets.get(i)))
                .collect(Collectors.toList());
    }

    @Override
    public <T> T accept(StmtVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String getInsnString() {
        return "lookupswitch";
    }
}
