package crux.calcite;

import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRule;
import java.util.Objects;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.rel.core.TableScan;
import clojure.lang.Keyword;

public class CruxTableScan extends TableScan implements CruxRel {
    private final CruxTable cruxTable;
    private final RelDataType projectRowType;

    protected CruxTableScan(RelOptCluster cluster, RelTraitSet traitSet,
                            RelOptTable table, CruxTable cruxTable,
                            RelDataType projectRowType) {
        super(cluster, traitSet, ImmutableList.of(), table);
        this.cruxTable  = Objects.requireNonNull(cruxTable, "cruxTable");
        this.projectRowType = projectRowType;

        assert getConvention() == CruxRel.CONVENTION;
    }

    @Override public void register(RelOptPlanner planner) {
        for (RelOptRule rule: CruxRules.RULES) {
            planner.addRule(rule);
        }
    }

    @Override public void implement(Implementor implementor) {
        implementor.table = table;
        implementor.schema = cruxTable.schema;
    }

    public CruxTable getCruxTable() {
        return cruxTable;
    }
}