package org.jruby.compiler.ir.dataflow.analyses;

import org.jruby.compiler.ir.IRClosure;
import org.jruby.compiler.ir.IRExecutionScope;
import org.jruby.compiler.ir.Operation;
import org.jruby.compiler.ir.dataflow.DataFlowProblem;
import org.jruby.compiler.ir.dataflow.FlowGraphNode;
import org.jruby.compiler.ir.instructions.Instr;
import org.jruby.compiler.ir.instructions.CallInstr;
import org.jruby.compiler.ir.instructions.LoadFromFrameInstr;
import org.jruby.compiler.ir.operands.Operand;
import org.jruby.compiler.ir.operands.MetaObject;
import org.jruby.compiler.ir.operands.Variable;
import org.jruby.compiler.ir.representations.BasicBlock;
import org.jruby.compiler.ir.representations.CFG;
import org.jruby.compiler.ir.representations.CFG.CFG_Edge;

import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import org.jruby.compiler.ir.operands.LocalVariable;

public class FrameLoadPlacementNode extends FlowGraphNode {
    /* ---------- Public fields, methods --------- */
    public FrameLoadPlacementNode(DataFlowProblem prob, BasicBlock n) {
        super(prob, n);
    }

    @Override
    public void init() {
        _inReqdLoads = new HashSet<Variable>();
        _outReqdLoads = new HashSet<Variable>();
    }

    // Only ruby local variables are candidates for frame loads.  Ignore the rest!
    // SSS FIXME: What about self?
    public void buildDataFlowVars(Instr i) {
        FrameLoadPlacementProblem flp = (FrameLoadPlacementProblem) _prob;
        for (Variable v : i.getUsedVariables()) {
            if (v instanceof LocalVariable)
                flp.recordUsedVar(v);
        }

        Variable v = i.getResult();
        if ((v != null) && (v instanceof LocalVariable)) flp.recordDefVar(v);
    }

    public void initSolnForNode() {
        if (_bb == _prob.getCFG().getExitBB()) {
            _inReqdLoads = ((FrameLoadPlacementProblem) _prob).getLoadsOnScopeExit();
        }
    }

    public void compute_MEET(CFG_Edge edge, FlowGraphNode pred) {
        FrameLoadPlacementNode n = (FrameLoadPlacementNode) pred;
        _inReqdLoads.addAll(n._outReqdLoads);
    }

    public boolean applyTransferFunction() {
        FrameLoadPlacementProblem flp = (FrameLoadPlacementProblem) _prob;
        Set<Variable> reqdLoads = new HashSet<Variable>(_inReqdLoads);

        List<Instr> instrs = _bb.getInstrs();
        ListIterator<Instr> it = instrs.listIterator(instrs.size());
        while (it.hasPrevious()) {
            Instr i = it.previous();

            if (i.operation == Operation.FRAME_STORE) continue;

            // Right away, clear the variable defined by this instruction -- it doesn't have to be loaded!
            Variable r = i.getResult();

            if (r != null) reqdLoads.remove(r);

            // Process calls specially -- these are the sites of frame loads!
            if (i instanceof CallInstr) {
                CallInstr call = (CallInstr) i;
                Operand o = call.getClosureArg();
                if ((o != null) && (o instanceof MetaObject)) {
                    IRClosure cl = (IRClosure) ((MetaObject) o).scope;
                    CFG cl_cfg = cl.getCFG();
                    FrameLoadPlacementProblem cl_flp = new FrameLoadPlacementProblem();
                    cl_flp.initLoadsOnScopeExit(reqdLoads);
                    cl_flp.setup(cl_cfg);
                    cl_flp.compute_MOP_Solution();
                    cl_cfg.setDataFlowSolution(cl_flp.getName(), cl_flp);

                    // Variables defined in the closure do not need to be loaded anymore at
                    // program points before the call.
                    Set<Variable> newReqdLoads = new HashSet<Variable>(reqdLoads);
                    for (Variable v : reqdLoads) {
                        if (cl_flp.scopeDefinesVariable(v)) newReqdLoads.remove(v);
                    }
                    reqdLoads = newReqdLoads;
                } // In this case, we are going to blindly load everything -- so, at the call site, pending loads dont carry over!
                else if (call.requiresFrame()) {
                    reqdLoads.clear();
                }
            }

            // The variables used as arguments will need to be loaded
            for (Variable x : i.getUsedVariables()) {
                if (x instanceof LocalVariable)
                    reqdLoads.add(x);
            }
        }

        // At the beginning of the scope, required loads can be discarded.
        if (_bb == _prob.getCFG().getEntryBB()) reqdLoads.clear();

        if (_outReqdLoads.equals(reqdLoads)) {
            return false;
        } else {
            _outReqdLoads = reqdLoads;
            return true;
        }
    }

    @Override
    public String toString() {
        return "";
    }

    public void addLoads() {
        FrameLoadPlacementProblem flp = (FrameLoadPlacementProblem) _prob;
        IRExecutionScope s = flp.getCFG().getScope();
        List<Instr> instrs = _bb.getInstrs();
        ListIterator<Instr> it = instrs.listIterator(instrs.size());
        Set<Variable> reqdLoads = new HashSet<Variable>(_inReqdLoads);
        while (it.hasPrevious()) {
            Instr i = it.previous();

            if (i.operation == Operation.FRAME_STORE) continue;

            // Right away, clear the variable defined by this instruction -- it doesn't have to be loaded!
            Variable r = i.getResult();

            if (r != null) reqdLoads.remove(r);

            if (i instanceof CallInstr) {
                CallInstr call = (CallInstr) i;
                Operand o = call.getClosureArg();
                if ((o != null) && (o instanceof MetaObject)) {
                    CFG cl_cfg = ((IRClosure) ((MetaObject) o).scope).getCFG();
                    FrameLoadPlacementProblem cl_flp = (FrameLoadPlacementProblem) cl_cfg.getDataFlowSolution(flp.getName());

                    // Only those variables that are defined in the closure, and are in the required loads set 
                    // will need to be loaded from the frame after the call!
                    Set<Variable> newReqdLoads = new HashSet<Variable>(reqdLoads);
                    it.next();
                    for (Variable v : reqdLoads) {
                        if (cl_flp.scopeDefinesVariable(v)) {
                            it.add(new LoadFromFrameInstr(v, s, v.getName()));
                            it.previous();
                            newReqdLoads.remove(v);
                        }
                    }
                    it.previous();
                    reqdLoads = newReqdLoads;

                    // add loads in the closure
                    ((FrameLoadPlacementProblem) cl_cfg.getDataFlowSolution(flp.getName())).addLoads();
                } else if (call.requiresFrame()) {
                    it.next();
                    for (Variable v : reqdLoads) {
                        it.add(new LoadFromFrameInstr(v, s, v.getName()));
                        it.previous();
                    }
                    it.previous();
                    reqdLoads.clear();
                }
            }

            // The variables used as arguments will need to be loaded
            for (Variable x : i.getUsedVariables()) {
                if (x instanceof LocalVariable)
                    reqdLoads.add(x);
            }
        }

        // Load first use of variables in closures
        if ((s instanceof IRClosure) && (_bb == _prob.getCFG().getEntryBB())) {
            for (Variable v : reqdLoads) {
                if (flp.scopeUsesVariable(v)) {
                    it.add(new LoadFromFrameInstr(v, s, v.getName()));
                }
            }
        }
    }

    /* ---------- Private fields, methods --------- */
    Set<Variable> _inReqdLoads;     // On entry to flow graph node:  Variables that need to be loaded from the heap frame
    Set<Variable> _outReqdLoads;    // On exit from flow graph node: Variables that need to be loaded from the heap frame
}
