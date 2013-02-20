package edu.colorado.thresher;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceFieldKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.StaticFieldKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.shrikeBT.ConditionalBranchInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.intset.OrdinalSet;

/**
 * Path and points-to query that share information to make each more precise.
 * 
 * @author sam
 */

public class CombinedPathAndPointsToQuery extends PathQuery {
  
  // special class just to allow us to override methods of PointsToQuery
  private final class PointsToQueryWrapper extends PointsToQuery {
    private final PointsToQuery delegate;
    public PointsToQueryWrapper(PointsToQuery qry) {
      super(qry.constraints, qry.produced, qry.witnessList, qry.depRuleGenerator);
      this.delegate = qry;
    }
    
    @Override
    public boolean isRuleRelevant(DependencyRule rule, IPathInfo currentPath, Set<PointerVariable> extraVars) {
      return delegate.isRuleRelevant(rule, currentPath, extraVars) || 
             CombinedPathAndPointsToQuery.this.isRuleRelevantForPathQuery(rule, currentPath, extraVars);
    }
    
    @Override
    public PointsToQueryWrapper deepCopy() {
      return new PointsToQueryWrapper(delegate.deepCopy());
    }
  }

  final PointsToQueryWrapper pointsToQuery;
  boolean fakeWitness = false;

  public CombinedPathAndPointsToQuery(PointsToQuery pointsToQuery) {
    super(pointsToQuery.depRuleGenerator);
    this.pointsToQuery = new PointsToQueryWrapper(pointsToQuery);
  }

  CombinedPathAndPointsToQuery(PointsToQueryWrapper pointsToQuery, PathQuery pathQuery) {
    super(pathQuery.constraints, pathQuery.pathVars, pathQuery.witnessList, pathQuery.depRuleGenerator, pathQuery.ctx); // pathQuery.ctx);
    this.pointsToQuery = pointsToQuery;
  }

  /**
   * @return true if the query has been successfully witnessed, false otherwise
   */
  public boolean foundWitness() {
    return fakeWitness || (super.foundWitness() && pointsToQuery.foundWitness());
  }

  @Override
  public CombinedPathAndPointsToQuery deepCopy() {
    return new CombinedPathAndPointsToQuery(pointsToQuery.deepCopy(), super.deepCopy());
  }

  @Override
  public boolean isFeasible() {
    return super.isFeasible() && pointsToQuery.isFeasible();
  }

  @Override
  public void intersect(IQuery other) {
    Util.Assert(other instanceof CombinedPathAndPointsToQuery, "Not sure how to deal with query type " + other.getClass());
    CombinedPathAndPointsToQuery query = (CombinedPathAndPointsToQuery) other;
    super.intersect((PathQuery) query);
  }

  @Override
  public List<IQuery> visitPhi(SSAPhiInstruction instr, int phiIndex, IPathInfo currentPath) {
    List<IQuery> pathResults = super.visitPhi(instr, phiIndex, currentPath);
    if (pathResults == IQuery.INFEASIBLE)
      return IQuery.INFEASIBLE;
    Util.Assert(pathResults.isEmpty(), "should never be case splits on path constraints!");

    List<IQuery> ptResults = pointsToQuery.visitPhi(instr, phiIndex, currentPath);
    if (ptResults == IQuery.INFEASIBLE)
      return IQuery.INFEASIBLE;
    Util.Debug("CONS " + this.toString());
    return combinePathAndPointsToQueries(ptResults, pathResults);
  }

  @Override
  boolean visit(SSANewInstruction instr, CGNode node, SymbolTable tbl) {
    PointerVariable local = new ConcretePointerVariable(node, instr.getDef(), this.depRuleGenerator.getHeapModel());
    if (pathVars.contains(local)) {
      // special case for arrays
      if (instr.getNewSite().getDeclaredType().isArrayType()) { 
        // may need to update path constraints with the length of this array
        SimplePathTerm arrLength;
        if (tbl.isConstant(instr.getUse(0))) {
          arrLength = new SimplePathTerm(tbl.getIntValue(instr.getUse(0)));
        } else {
          arrLength = new SimplePathTerm(new ConcretePointerVariable(node, instr.getUse(0), this.depRuleGenerator.getHeapModel()));
        }
        substituteExpForFieldRead(arrLength, local, SimplePathTerm.LENGTH);
      }

      InstanceKey key = depRuleGenerator.getHeapModel().getInstanceKeyForAllocation(node, instr.getNewSite());
      if (key != null) {
        PointerVariable newVar = Util.makePointerVariable(key);
        substituteExpForVar(new SimplePathTerm(newVar), local);
      }
      return isFeasible();
    }
    return true; // didn't add any constraints, can't be infeasible
  }

  @Override
  public List<IQuery> visit(SSAInstruction instr, IPathInfo currentPath, Set<PointsToEdge> refuted) {
    // visit path constraints first, since they can't cause case-splits
    List<IQuery> pathResults = super.visit(instr, currentPath, refuted);
    if (pathResults == IQuery.INFEASIBLE) return IQuery.INFEASIBLE;
    Util.Assert(pathResults.isEmpty(), "should never be case splits on path constraints!");

    List<IQuery> ptResults = pointsToQuery.visit(instr, currentPath, this.pathVars, refuted);
    if (ptResults == IQuery.INFEASIBLE) return IQuery.INFEASIBLE;
    if (Options.DEBUG) Util.Debug("CONS " + this.toString());
    return combinePathAndPointsToQueries(ptResults, pathResults);
  }

  @Override
  public List<IQuery> visit(SSAInstruction instr, IPathInfo currentPath) {
    Set<PointsToEdge> edgeSet = HashSetFactory.make();
    List<IQuery> ptResults = pointsToQuery.visit(instr, currentPath, this.pathVars, edgeSet);
    if (ptResults == IQuery.INFEASIBLE) return IQuery.INFEASIBLE;
    if (Options.DEBUG) Util.Debug("CONS " + this.toString());

    List<IQuery> pathResults = super.visit(instr, currentPath);
    if (pathResults == IQuery.INFEASIBLE) return IQuery.INFEASIBLE;
    Util.Assert(pathResults.isEmpty(), "should never be case splits on path constraints!");

    return combinePathAndPointsToQueries(ptResults, pathResults);
  }

  @Override
  public List<IQuery> enterCall(SSAInvokeInstruction instr, CGNode callee, IPathInfo currentPath) {
    List<IQuery> ptResults = pointsToQuery.enterCall(instr, callee, currentPath, this.pathVars);
    if (ptResults == IQuery.INFEASIBLE) return IQuery.INFEASIBLE;
    Util.Assert(ptResults.isEmpty(), "Unimp: handling case splits at calls!");
    List<IQuery> pathResults = super.enterCall(instr, callee, currentPath);
    if (pathResults == IQuery.INFEASIBLE) return IQuery.INFEASIBLE;
    return combinePathAndPointsToQueries(ptResults, pathResults);
  }

  /**
   * if we are entering a call from a jump, we cannot do parameter binding
   * directly, as we do normally instead, we must be a a bit more clever and use
   * the dependency rules to help us make relevant bindings
   */
  @Override
  public void enterCallFromJump(SSAInvokeInstruction instr, CGNode callee, IPathInfo currentPath) {
    this.pointsToQuery.enterCallFromJump(instr, callee, currentPath);
    // if params were bound, they will be in the produced set. apply these param
    // bindings to the path constraints
    for (PointsToEdge constraint : pointsToQuery.produced) {
      if (this.pathVars.contains(constraint.getSink())) {
        substituteExpForVar(new SimplePathTerm(constraint.getSource()), constraint.getSink());

      }
    }
    /*
     * Set<DependencyRule> rulesProducedByCall =
     * depRuleGenerator.getRulesForInstr(instr, currentPath.getCurrentNode());
     * Util.Assert(rulesProducedByCall != null, "no rules for call " + instr +
     * " from " + currentPath.getCurrentNode()); for (DependencyRule rule :
     * rulesProducedByCall) { Util.Debug("rule produced by call " + rule);
     * PointsToEdge shown = rule.getShown(); PointerVariable snk =
     * shown.getSink(); if (this.pathVars.contains(snk)) { // sub heap loc for
     * its local pointer Util.Debug("enter call: subbing " + shown.getSource() +
     * " for " + snk); substituteExpForVar(new
     * SimplePathTerm(shown.getSource()), snk); } }
     */
  }

  @Override
  public void declareFakeWitness() {
    if (Options.DEBUG)
      Util.Debug("declaring fake witness");
    this.fakeWitness = true;
  }

  @Override
  public boolean containsStaleConstraints(CGNode currentNode) {
    return pointsToQuery.containsStaleConstraints(currentNode) || super.containsStaleConstraints(currentNode);
  }

  @Override
  public List<IQuery> returnFromCall(SSAInvokeInstruction instr, CGNode callee, IPathInfo currentPath) {
    List<IQuery> ptResults = pointsToQuery.returnFromCall(instr, callee, currentPath);
    if (ptResults == IQuery.INFEASIBLE)
      return IQuery.INFEASIBLE;
    List<IQuery> pathResults = super.returnFromCall(instr, callee, currentPath);
    if (pathResults == IQuery.INFEASIBLE)
      return IQuery.INFEASIBLE;
    return combinePathAndPointsToQueries(ptResults, pathResults);
  }

  /**
   * @return - cartesian product of two lists of case splits; one points-to and
   *         one path
   */
  List<IQuery> combinePathAndPointsToQueries(List<IQuery> pointsToQueries, List<IQuery> pathQueries) {
    boolean ptEmpty = pointsToQueries == IQuery.FEASIBLE, pathEmpty = pathQueries == IQuery.FEASIBLE;
    if (ptEmpty && pathEmpty)
      return IQuery.FEASIBLE;
    List<IQuery> combinedQuery = new LinkedList<IQuery>();
    if (!ptEmpty && !pathEmpty) {
      Util.Unimp("case split in path and points-to");
      // TODO: would need to mix in current query here as well, which would get
      // messy...
      for (IQuery ptQuery : pointsToQueries) {
        for (IQuery pathQuery : pathQueries) {
          ptQuery.deepCopy();
          pathQuery.deepCopy();
          combinedQuery.add(new CombinedPathAndPointsToQuery((PointsToQueryWrapper) ptQuery.deepCopy(), (PathQuery) pathQuery.deepCopy()));
        }
      }
    } else if (!pathEmpty) {
      for (IQuery pathQuery : pathQueries) {
        combinedQuery.add(new CombinedPathAndPointsToQuery(pointsToQuery.deepCopy(), (PathQuery) pathQuery.deepCopy()));
      }
    } else if (!ptEmpty) {
      for (IQuery ptQuery : pointsToQueries) {
        combinedQuery.add(new CombinedPathAndPointsToQuery((PointsToQueryWrapper) ptQuery, super.deepCopy()));
      }
    }
    return combinedQuery;
  }

  @Override
  public boolean addContextualConstraints(CGNode node, IPathInfo currentPath) {
    return pointsToQuery.addContextualConstraints(node, currentPath) && super.addContextualConstraints(node, currentPath);
  }

  @Override
  public boolean isCallRelevant(SSAInvokeInstruction instr, CGNode caller, CGNode callee, CallGraph cg) {
    return pointsToQuery.isCallRelevant(instr, caller, callee, cg)
        || this.doesCallWriteToHeapLocsInPathConstraints(instr, caller, callee, cg);
  }

  @Override
  public void removeLoopProduceableConstraints(SSACFG.BasicBlock loopHead, CGNode currentNode) {
    if (!Options.LOOP_INVARIANT_INFERENCE) pointsToQuery.removeLoopProduceableConstraints(loopHead, currentNode);
    // else, only need to drop path constraints; we're computing a fixed point
    // over pt-constraints
    // super.removeLoopProduceableConstraints(loopHead, currentNode);
    // we only need to remove path constraints produceable in the loop... we
    // don't want to remove pts-to constraints; we are computing a fixed point
    // over those
    // dropConstraintsProuceableByRuleSet(Util.getRulesForLoop(loopHead,
    // currentNode, depRuleGenerator, depRuleGenerator.getCallGraph()));
    dropPathConstraintsWrittenInLoop(loopHead, currentNode);
  }

  private void dropPathConstraintsWrittenInLoop(SSACFG.BasicBlock loopHead, CGNode node) {
    if (this.constraints.isEmpty()) return;
    Set<DependencyRule> loopRules = new TreeSet<DependencyRule>();
    // get all rules for node
    Set<DependencyRule> rules = depRuleGenerator.getRulesForNode(node);

    if (rules != null) {
      for (DependencyRule rule : rules) { // keep only rules produced in loop
        Util.Assert(rule.getBlock() != null, "no basic block for rule " + rule);
        if (WALACFGUtil.isInLoopBody(rule.getBlock(), loopHead, node.getIR())) {
          loopRules.add(rule);
        }
      }
      // remove all constraints produceable by one of these rules
      dropConstraintsProuceableByRuleSet(loopRules); 
    }

    // check for additional relevant keys by consulting the points-to analysis
    ClassHierarchy cha = depRuleGenerator.getClassHierarchy();
    HeapModel hm = depRuleGenerator.getHeapModel();

    // the loop may also contain callees. drop any constraint containing vars
    // that these callees can write to
    Set<CGNode> targets = WALACFGUtil.getCallTargetsInLoop(loopHead, node, depRuleGenerator.getCallGraph());
    Set<AtomicPathConstraint> toDrop = HashSetFactory.make(); //new HashSet<AtomicPathConstraint>();
    // drop all vars that can be written by a call in the loop body
    for (CGNode callNode : targets) { 
      OrdinalSet<PointerKey> callKeys = depRuleGenerator.getModRef().get(callNode);

      for (AtomicPathConstraint constraint : constraints) {
        for (PointerKey key : constraint.getPointerKeys(depRuleGenerator)) {
          if (callKeys.contains(key)) {
            toDrop.add(constraint);
            break;
          }
          // else, check for refs in pts-to constraints
          Set<SimplePathTerm> terms = constraint.getTerms();
          for (SimplePathTerm term : terms) {
            if (term.getObject() != null && term.getFields() != null) {
              PointerVariable pointedTo = this.pointsToQuery.getPointedTo(term.getObject());
              if (pointedTo != null && pointedTo.getInstanceKey() instanceof InstanceKey) {
                FieldReference fieldRef = term.getFirstField();
                if (fieldRef == null) continue;
                IField fld = cha.resolveField(fieldRef);
                if (fld == null) continue;
                PointerKey fieldKey = hm.getPointerKeyForInstanceField((InstanceKey) pointedTo.getInstanceKey(), fld);
                if (fieldKey == null) continue;
                if (callKeys.contains(fieldKey)) {
                  toDrop.add(constraint);
                  break;
                }
              }
            }
          }
        }
      }

    }
    for (AtomicPathConstraint dropMe : toDrop) {
      if (Options.DEBUG) Util.Debug("dropping loop constraint " + dropMe);
      removeConstraint(dropMe);
    }
  }

  @Override
  public boolean containsLoopProduceableConstraints(SSACFG.BasicBlock loopHead, CGNode currentNode) {
    return pointsToQuery.containsLoopProduceableConstraints(loopHead, currentNode)
        || super.containsLoopProduceableConstraints(loopHead, currentNode);
  }

  @Override
  public boolean contains(IQuery other) {
    if (!(other instanceof CombinedPathAndPointsToQuery))
      return false;
    CombinedPathAndPointsToQuery otherQuery = (CombinedPathAndPointsToQuery) other;
    // return this.pointsToQuery.contains(otherQuery.pointsToQuery);
    return this.pointsToQuery.contains(otherQuery.pointsToQuery) && super.symbContains(otherQuery);
    // && super.constraints.containsAll(otherQuery.constraints);
  }

  @Override
  public void dropConstraintsProduceableInCall(SSAInvokeInstruction instr, CGNode caller, CGNode callee) {
    this.pointsToQuery.dropConstraintsProduceableInCall(instr, caller, callee);
    this.dropPathConstraintsProduceableByCall(instr, caller, callee);
    if (this.foundWitness())
      Util.Debug("dropping constraints led to FAKE witness!");
    // Util.Assert(!this.foundWitness(),
    // "dropping constraints led to fake witness!");
  }

  void dropConstraintsProuceableByRuleSet(Set<DependencyRule> rules) {
    Set<PointerVariable> toDrop = HashSetFactory.make(); //new HashSet<PointerVariable>();
    Set<PointerVariable> relevantVars = HashSetFactory.make();// new HashSet<PointerVariable>();
    for (PointerVariable var : this.pathVars) {
      if (!var.isLocalVar())
        relevantVars.add(var);
      else { // this is a local
        // try to use pts-to constraints to determine which heap loc this local corresponds to
        PointerVariable pointedTo = this.pointsToQuery.getPointedTo(var);
        if (pointedTo == null) {
          // can't find this var in our points-to constraints; no telling what it might point to. must drop it.
          toDrop.add(var);
        }
        relevantVars.add(pointedTo);
      }
    }
    for (PointerVariable var : toDrop) dropConstraintsContaining(var);
    relevantVars.removeAll(toDrop);

    Set<AtomicPathConstraint> toRemove = new HashSet<AtomicPathConstraint>();
    for (AtomicPathConstraint constraint : this.constraints) {
      Set<PointerVariable> vars = constraint.getVars();
      Set<FieldReference> fields = constraint.getFields();
      // see if a dependency rules can write to one of the heap loc's in the path constraints
      for (DependencyRule rule : rules) {
        PointerVariable src = rule.getShown().getSource(); 
        if (vars.contains(src))
          toRemove.add(constraint);
        if (rule.getShown().getFieldRef() != null && fields.contains(rule.getShown().getFieldRef().getReference()))
          toRemove.add(constraint);
      }
    }
    for (AtomicPathConstraint constraint : toRemove) {
      if (Options.DEBUG) Util.Debug("dropping constraint produceable by rule set" + constraint);
      removeConstraint(constraint);
    }
    super.rebuildZ3Constraints();
  }

  void dropPathConstraintsProduceableByCall(SSAInvokeInstruction instr, CGNode caller, CGNode callee) {
    ConcretePointerVariable retval = null;
    if (instr.hasDef()) {
      retval = new ConcretePointerVariable(caller, instr.getDef(), this.depRuleGenerator.getHeapModel());
      dropConstraintsContaining(retval);
    }
   
    List<AtomicPathConstraint> toDrop = new LinkedList<AtomicPathConstraint>();
    OrdinalSet<PointerKey> keys = this.depRuleGenerator.getModRef().get(callee);
    for (AtomicPathConstraint constraint : constraints) {
      for (PointerKey key : constraint.getPointerKeys(depRuleGenerator)) {
        if (keys.contains(key)) {
          toDrop.add(constraint);
          break;
        }
      }
    }
    for (AtomicPathConstraint dropMe : toDrop)
      removeConstraint(dropMe); // constraints.remove(dropMe);
  }

  boolean doesCallWriteToHeapLocsInPathConstraints(SSAInvokeInstruction instr, CGNode caller, CGNode callee, CallGraph cg) {
    // do constraints contain retval of this call?
    if (instr.hasDef()) {
      ConcretePointerVariable retval = new ConcretePointerVariable(caller, instr.getDef(), this.depRuleGenerator.getHeapModel());
      if (this.pathVars.contains(retval))
        return true; // constraints contain retval; definitely relevant
    }

    // do constraints contain a param passed to this function?
    SymbolTable tbl = caller.getIR().getSymbolTable();
    for (int i = 0; i < instr.getNumberOfParameters(); i++) {
      if (!tbl.isConstant(instr.getUse(i))) {
        PointerVariable arg = new ConcretePointerVariable(caller, instr.getUse(i), this.depRuleGenerator.getHeapModel());
        // constraints contain a non-constant param that's passed to this function; possibly relevant
        if (this.pathVars.contains(arg)) return true; 
      }
    }
    
    // does this call modify our path constraints according to its precomputed mod set?
    OrdinalSet<PointerKey> keys = this.depRuleGenerator.getModRef().get(callee);
    for (AtomicPathConstraint constraint : constraints) {
      for (PointerKey key : constraint.getPointerKeys(depRuleGenerator)) {
      if (keys.contains(key)) return true; // mod set says yes
        if (key instanceof StaticFieldKey) {
          IClass declaringClass = ((StaticFieldKey) key).getField().getDeclaringClass();
          // if this is a <clinit>, might initialize field to default values
          return declaringClass.equals(callee.getMethod().getDeclaringClass())
              && (callee.getMethod().isClinit());
        } 
      }
    }

    return false;
  }

  @Override
  public void removeAllLocalConstraints() {
    // IMPORTANT! must do this first, otherwise we lose the local pts-to info!
    this.removeAllLocalPathConstraints(); 
    pointsToQuery.removeAllLocalConstraints();
  }

  /**
   * take advantage of pts-to information to sub out locals for their heap
   * value, if known
   */
  public void removeAllLocalPathConstraints() {
    // first, sub out all locals for their corresponding heap locations, if we know them
    for (PointsToEdge edge : pointsToQuery.constraints) {
      PointerVariable src = edge.getSource();
      if (src.isLocalVar() && this.pathVars.contains(src)) {
        if (Options.DEBUG)
          Util.Debug("subbing out for " + src + "; replacing with " + edge.getSink());
        // do substitution for snk of edge
        this.substituteExpForVar(new SimplePathTerm(edge.getSink()), src);
      }
    }

    for (PointsToEdge edge : pointsToQuery.produced) {
      PointerVariable src = edge.getSource();
      if (src.isLocalVar() && this.pathVars.contains(src)) {
        if (Options.DEBUG)
          Util.Debug("subbing out for " + src + "; replacing with " + edge.getSink());
        // do substitution for snk of edge
        this.substituteExpForVar(new SimplePathTerm(edge.getSink()), src);
      }
    }

    // now, can remove all local constraints
    List<AtomicPathConstraint> toRemove = new LinkedList<AtomicPathConstraint>();
    for (AtomicPathConstraint constraint : constraints) {
      for (PointerVariable var : constraint.getVars()) {
        if (var.isLocalVar())
          toRemove.add(constraint);
        break;
      }
    }
    for (AtomicPathConstraint constraint : toRemove)
      removeConstraint(constraint);// constraints.remove(constraint);
  }

  @Override
  public boolean addConstraintFromBranchPoint(IBranchPoint point, boolean trueBranchFeasible) {
    return this.addConstraintFromBranchPointAndCheckForNull(point, trueBranchFeasible)
        && pointsToQuery.addConstraintFromBranchPoint(point, trueBranchFeasible);
  }

  public boolean addConstraintFromBranchPointAndCheckForNull(IBranchPoint point, boolean trueBranchFeasible) {
    SSAConditionalBranchInstruction instruction = point.getInstr();
    CGNode method = point.getMethod();
    SymbolTable tbl = point.getSymbolTable();
    // is this a comparison of constants?
    // if (instruction.isIntegerComparison() &&
    // tbl.isConstant(instruction.getUse(0)) &&
    // tbl.isConstant(instruction.getUse(1))) {
    if (tbl.isConstant(instruction.getUse(0)) && tbl.isConstant(instruction.getUse(1))) {
      // yes, so we can determine immediately whether this branch can be taken
      // (no need to add path constraints).
      return evaluateGuard(instruction, tbl, !trueBranchFeasible); // we should
                                                                   // negate the
                                                                   // path
                                                                   // constraint
                                                                   // if the
                                                                   // true
                                                                   // branch is
                                                                   // infeasible
    } else { // no. extract the path constraint from the branch condition
      AtomicPathConstraint constraint = getPathConstraintFromGuard(instruction, tbl, method, !trueBranchFeasible);
      if (constraint == null)
        return true; // null return means its a string comparison or something
                     // else we don't support. bail outc

      // check for null comparison against something that's already in the
      // pts-to constraints
      if (constraint.getLhs() instanceof SimplePathTerm && constraint.getRhs() instanceof SimplePathTerm) {
        SimplePathTerm lhs = (SimplePathTerm) constraint.getLhs(), rhs = (SimplePathTerm) constraint.getRhs();
        PointerVariable comparedToNull = null;
        if (lhs.isIntegerConstant() && lhs.getIntegerConstant() == 0)
          comparedToNull = rhs.getObject();
        else if (rhs.isIntegerConstant() && rhs.getIntegerConstant() == 0)
          comparedToNull = lhs.getObject();
        if (comparedToNull != null) {
          ConditionalBranchInstruction.Operator op = constraint.getOp();
          // this is a null comparison; see if this appears in the pts-to
          // constraints
          for (PointsToEdge edge : pointsToQuery.produced) {
            if (edge.getSource().equals(comparedToNull)) {
              if (op == ConditionalBranchInstruction.Operator.EQ) {
                if (Options.DEBUG)
                  Util.Debug("refuted by comparsion to null; pts-to constraints require var " + comparedToNull + " to be non-null!");
                this.feasible = false;
                return false;
              } else if (op == ConditionalBranchInstruction.Operator.NE) {
                // already know this can't be null; constraint is satisfied
                return true;
              }
            }
          }

          for (PointsToEdge edge : pointsToQuery.constraints) {
            if (edge.getSource().equals(comparedToNull)) {
              if (op == ConditionalBranchInstruction.Operator.EQ) {
                if (Options.DEBUG)
                  Util.Debug("refuted by comparsion to null; pts-to constraints require var " + comparedToNull + " to be non-null!");
                this.feasible = false;
                return false;
              } else if (op == ConditionalBranchInstruction.Operator.NE) {
                // already know this can't be null; constraint is satisfied
                return true;
              }
            }
          }
        }
      }

      if (addConstraint(constraint))
        return isFeasible();
      return true; // else, constraint already in set; no need to check
                   // feasibility
    }
  }

  @Override
  public void intializeStaticFieldsToDefaultValues() {
    pointsToQuery.intializeStaticFieldsToDefaultValues();
    super.intializeStaticFieldsToDefaultValues();
  }

  private Map<Constraint, Set<CGNode>> getModifiersForQueryHelper() {
    Map<PointerKey, Set<CGNode>> reversedModRef = this.depRuleGenerator.getReversedModRef();
    Map<Constraint, Set<CGNode>> constraintModMap = HashMapFactory.make();//new HashMap<Constraint, Set<CGNode>>();
    for (AtomicPathConstraint constraint : this.constraints) {
      Set<CGNode> nodes = new HashSet<CGNode>();
      addInitsForConstraintFields(constraint, nodes);
      // addClassInitsForConstraintFields(constraint, nodes); // add class init
      // if it may write to the constraint
      for (PointerKey key : constraint.getPointerKeys(depRuleGenerator)) {
        Set<CGNode> modRefNodes = reversedModRef.get(key);
        // this can happen when var is the this param for a class with no fields
        if (modRefNodes == null) continue;
        for (CGNode node : modRefNodes) {
          // add to mapping *only* if node modifies pointer key directly (not via callees)
          // this is because the use of the reversed mod/ref is to jump directly to
          // the node that might modify our key of interest
          if (Util.writesKeyLocally(node, key, this.depRuleGenerator.getHeapModel(), 
              this.depRuleGenerator.getHeapGraph(), this.depRuleGenerator.getClassHierarchy())) {
            nodes.add(node);
          }
        }
      }
      constraintModMap.put(constraint, nodes);
    }
    return constraintModMap;
  }
  
  private void addInitsForConstraintFields(AtomicPathConstraint constraint, Set<CGNode> nodes) {
    for (PointerKey key : constraint.getPointerKeys(depRuleGenerator)) {
      if (key instanceof InstanceFieldKey) {
        IField fieldKey = ((InstanceFieldKey) key).getField(); 
        for (IMethod method : fieldKey.getDeclaringClass().getDeclaredMethods()) {
          if (method.isInit()) {
            nodes.addAll(this.depRuleGenerator.getCallGraph().getNodes(method.getReference()));
          }
        }
      }
    }
  }
  
  public boolean isRuleRelevantForPathQuery(DependencyRule rule, IPathInfo currentPath, Set<PointerVariable> extraVars) {
    PointsToEdge edge = rule.getShown();
    if (this.pathVars.contains(edge.getSource())) return true;
    if (edge.getSink().isSymbolic()) {
      SymbolicPointerVariable symb = (SymbolicPointerVariable) edge.getSink();
      Set<InstanceKey> possibleValues = symb.getPossibleValues();
      for (PointerVariable var : pathVars) {
        Object key = var.getInstanceKey();
        if (key != null && key instanceof InstanceKey && possibleValues.contains((InstanceKey) key)) return true;
      }
    } else return this.pathVars.contains(edge.getSink());
    Util.Assert(!this.toString().contains(edge.getSink().toString()), "problem getting relevance of " + edge + " to " + this);
    return false;
  }

  /**
   * add the class init for each field to the set of relevant nodes. need to do
   * this because class inits can write to fields by writing their default
   * values. this implicit write is not reflected in the normal mod/ref analysis
   * 
   * @param constraint
   * @param nodes
   */
  /*
    private void addClassInitsForConstraintFields(AtomicPathConstraint constraint, Set<CGNode> nodes) {
      Util.Debug("adding clinits for constraint " + constraint); 
      for (PointerKey key : constraint.getPointerKeys()) { 
        IField fieldKey; 
        if (key instanceof InstanceFieldKey) fieldKey = ((InstanceFieldKey) key).getField(); 
        else if (key instanceof StaticFieldKey) fieldKey = ((StaticFieldKey) key).getField(); 
        else continue; 
        IMethod clinit= fieldKey.getDeclaringClass().getClassInitializer();
        Util.Debug("classInit is " + clinit); 
        MethodReference classInit = fieldKey.getDeclaringClass().getClassInitializer().getReference();
        Set<CGNode> classInitNodes = this.depRuleGenerator.getCallGraph().getNodes(classInit);
        Util.Assert(classInitNodes.size() == 1); // should only be one class init
        nodes.add(classInitNodes.iterator().next()); 
      } 
   }
   */

  @Override
  public Map<Constraint, Set<CGNode>> getModifiersForQuery() {
    Map<Constraint, Set<CGNode>> mods = pointsToQuery.getModifiersForQuery();
    mods.putAll(getModifiersForQueryHelper());
    return mods;
  }

  @Override
  public boolean containsConstraint(Constraint constraint) {
    if (constraint instanceof PointsToEdge)
      return pointsToQuery.containsConstraint(constraint);
    return super.containsConstraint(constraint);
  }

  @Override
  public List<DependencyRule> getWitnessList() {
    return pointsToQuery.getWitnessList();
  }

  /*
   * @Override public int compareTo(Object other) { if (!(other instanceof
   * CombinedPathAndPointsToQuery))
   * Util.Unimp("comparing with different kind of query");
   * CombinedPathAndPointsToQuery otherQuery = (CombinedPathAndPointsToQuery)
   * other; int ptComparison =
   * pointsToQuery.compareTo(otherQuery.pointsToQuery); if (ptComparison != 0)
   * return ptComparison; int pathComparison = super.compareTo((PathQuery)
   * otherQuery); return pathComparison; }
   */

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof CombinedPathAndPointsToQuery))
      return false;
    CombinedPathAndPointsToQuery otherQuery = (CombinedPathAndPointsToQuery) other;
    return this.pointsToQuery.equals(otherQuery.pointsToQuery) && super.equals((PathQuery) otherQuery);
  }

  @Override
  public int hashCode() {
    // /Util.Unimp("don't hash this query!");
    // return 5;
    return 37 * this.pointsToQuery.hashCode() + super.hashCode();
  }

  @Override
  public String toString() {
    return pointsToQuery.toString() + "\n" + super.toString();
  }

}
