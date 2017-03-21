/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package nju.xiaofanli.consistency.formula;

import java.util.*;

import nju.xiaofanli.consistency.context.ContextChange;
import nju.xiaofanli.consistency.context.Pattern;

/**
 *
 * @author bingying
 * exists
 */
public class ExistsFormula extends Formula{
    private String variable;//������
    private Pattern pattern;//pattern
    private Formula subFormula;//�ӹ�ʽ
    private LinkedList<SubNode> subNodes = new LinkedList<>();

    public ExistsFormula(String name) {
        super(name);
        value = false;
    }

    public String getVariable() {
        return variable;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public void setPattern(String var, Pattern pat) {
        variable = var;
        pattern = pat;
    }

    public Formula getSubFormula() {
        return subFormula;
    }

    public void setSubFormula(Formula subformula) {
        this.subFormula = subformula;
    }

    public LinkedList<SubNode> getSubNodes() {
        return subNodes;
    }

    public void setSubNodes(LinkedList<SubNode> subNodes) {
        this.subNodes = subNodes;
    }

    public void addSubNode(SubNode subNode) {
        subNodes.add(subNode);
    }

    @Override
    public void evaluateECC(Assignment node) {
        value = false;
        for(SubNode subNode : subNodes) {
            node.setVar(variable, subNode.getContext());
            subNode.evaluateECC(node);
            value = value || subNode.getValue();
            node.deleteVar(variable);
        }
    }

    @Override
    public void generateECC() {
        links = new HashSet<>();
        for(SubNode subNode : subNodes) {
            subNode.generateECC();
            if(subNode.getValue())
                links = Link.union(links, Link.linkCartesian(new Link(false, variable, subNode.getContext()), subNode.getLinks()));
        }
    }

    @Override
    public boolean affect(ContextChange change) {
        return change.getPattern() == pattern || subFormula.affect(change);
    }

    @Override
    public void evaluatePCC(Assignment node, ContextChange change) {
        if(affect(change)) {
            if (subFormula.affect(change)) {
                value = false;
                for (SubNode subNode : subNodes) {
                    node.setVar(variable, subNode.getContext());
                    subNode.evaluatePCC(node, change);
                    value = value || subNode.getValue();
                    node.deleteVar(variable);
                }
            }
            else if (change.getType() == ContextChange.ADDITION) {
                SubNode subNode = subNodes.getLast();
                node.setVar(variable, subNode.getContext());
                subNode.evaluateECC(node);
                value = value || subNode.getValue();
                node.deleteVar(variable);
            }
            else if (change.getType() == ContextChange.DELETION) {
                value = false;
                for (SubNode subNode : subNodes)
                    value = value || subNode.getValue();
            }
        }
    }

    @Override
    public void generatePCC(ContextChange change) {
        if(affect(change)) {
            if (subFormula.affect(change)) {
                links = new HashSet<>();
                for (SubNode subNode : subNodes) {
                    subNode.generatePCC(change);
                    if (subNode.getValue())
                        links = Link.union(links, Link.linkCartesian(new Link(false, variable, subNode.getContext()), subNode.getLinks()));
                }
            }
            else if (change.getType() == ContextChange.ADDITION) {
                SubNode subNode = subNodes.getLast();
                subNode.generateECC();
                if (subNode.getValue())
                    links = Link.union(links, Link.linkCartesian(new Link(false, variable, subNode.getContext()), subNode.getLinks()));
            }
            else if (change.getType() == ContextChange.DELETION) {
                links = new HashSet<>();
                for (SubNode subNode : subNodes) {
                    if (subNode.getValue()) {
                        links = Link.union(links, Link.linkCartesian(new Link(false, variable, subNode.getContext()), subNode.getLinks()));
                    }
                }
            }
        }
    }

    @Override
    public void setGoal(String goal) {
        goalLink = goal;
        if(goalLink.equals("true")) {
            subFormula.setGoal(goalLink);
        }
        else {
            subFormula.setGoal("null");
        }
    }

    @Override
    public ExistsFormula createInitialFormula() {
        ExistsFormula f = new ExistsFormula(type);
        f.value = value;
        f.variable = variable;
        f.pattern = pattern;
        f.subFormula = subFormula.createInitialFormula();
        return f;
    }

    @Override
    public String toString() {
        return "\u2203" + variable + " \u2208 " + pattern.getId() + (subFormula.needBrackets() ? "(" + subFormula + ")" : " " + subFormula);
    }

    @Override
    protected String getName4indentString() {
        return "\u2203" + variable + " \u2208 " + pattern.getId();
    }

    @Override
    protected List<Formula> getSubformula4indentString() {
        return Collections.singletonList(subFormula);
    }
}
