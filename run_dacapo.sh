decapo_projects=(
    "antlr"
    "bloat"
    "hsqldb"
    "chart"
    "xalan"
    "jython"
    "pmd"
    "eclipse"
    "fop"
    "luindex"
    "lusearch"
)

for project in "${decapo_projects[@]}"; do
    decapo_jars="java-benchmarks/dacapo-2006/${project}.jar:java-benchmarks/dacapo-2006/${project}-deps.jar"

    ./run_jellyfish.sh $project $decapo_jars 8
done
