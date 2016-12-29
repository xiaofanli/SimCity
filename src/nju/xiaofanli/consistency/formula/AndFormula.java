/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package nju.xiaofanli.consistency.formula;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import nju.xiaofanli.consistency.context.ContextChange;

/**
 *
 * @author bingying
 * and��ʽ
 */
public class AndFormula extends Formula {
    private Formula first; 
    private Formula second;
    
//    @SuppressWarnings("unused")
//	private static Log logger = LogFactory.getLog(AndFormula.class.getId());
    
    public AndFormula(String name) {
        super(name);
    }
    
    public void setSubFormula(Formula left,Formula right) {
        first = left;
        second = right;
        value = first.value && second.value;
    }
    
    public Formula getFirst() {
    	return first;
    }

    public Formula getSecond() {
    	return second;
    }
    
    @Override
    public void evaluateECC(Assignment node) {
        first.evaluateECC(node);
        second.evaluateECC(node);
        value = first.value && second.value;
    }

    @Override
    public void generateECC() {
        first.generateECC();
        second.generateECC();
        Set<Link> l1 = first.getLinks();
    	Set<Link> l2 = second.getLinks();
        
        if(first.value)
    		links = second.value ? Link.linkCartesian(l1, l2) : l2;
    	else
    		links = second.value ? l1 : Link.union(l1, l2);
    }

    @Override
    public boolean affect(ContextChange change) {
        return first.affect(change) || second.affect(change);
    }

    @Override
    public void evaluatePCC(Assignment node, ContextChange change) {
    	if(first.affect(change))
            first.evaluatePCC(node,change);
    	if(second.affect(change))
    		second.evaluatePCC(node,change);
        value = first.value && second.value;
    }

    @Override
    public void generatePCC(ContextChange change) {
    	if(affect(change)) {
            if(first.affect(change))
                first.generatePCC(change);
            if(second.affect(change))
                second.generatePCC(change);
            Set<Link> l1 = first.links;
            Set<Link> l2 = second.links;

            if (first.value)
                links = second.value ? Link.linkCartesian(l1, l2) : l2;
            else
                links = second.value ? l1 : Link.union(l1, l2);
        }
    }

    @Override
    public void setGoal(String goal) {
        goalLink = goal;
        first.setGoal(goalLink);
        second.setGoal(goalLink);
    }
    
	@Override
	public AndFormula createInitialFormula() {
		AndFormula f = new AndFormula(type);
    	f.value = value;
    	f.first = first.createInitialFormula();
    	f.second = second.createInitialFormula();
		return f;
	}

    @Override
    public String toString() {
        String s = first.needBrackets() ? "(" + first + ")" : first.toString();
        s += " \u2227 ";
        s += second.needBrackets() ? "(" + second + ")" : second.toString();
        return s;
    }

    @Override
    protected String getName4indentString() {
        return "and";
    }

    @Override
    protected List<Formula> getSubformula4indentString() {
        return Arrays.asList(first, second);
    }
}
