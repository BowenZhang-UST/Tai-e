export JAVA_HOME=./jdk/Contents/Home
PROJECT=$1
JAR=$2
THREADS=$3
LINK="python3 linker/jellyfish-link.py"
DIS="llvm-12/bin/llvm-dis"
AS="llvm-12/bin/llvm-as"

rm -rf ./output/groups/*
rm -rf ./output/logs/*
rm -rf ./output/bc/*.bc
rm -rf ./output/bc/*.ll

echo START translate ${PROJECT}:

start_time=$SECONDS

# 1. Scheduler & Synthesizer
echo "   jellyfish-schduler"
./gradlew run --no-rebuild \
              --args="-a jelly-scheduler=classes-per-group:500 \
                      -java 6 \
                      --allow-phantom \
                      -acp \
                      ${JAR}" > output/logs/synthesis.txt

# 2. Translator
process_group() {
    local group="$1"
    echo "Processing group $group"
    ./gradlew run --no-rebuild \
                      --args="-a jelly-fish=group-to-translate:${group};project-name:${PROJECT} \
                            -java 6 \
                            --allow-phantom \
                            -acp \
                            ${JAR}" > "output/logs/group${group}.txt"
}

pids=()
N=$(find ./output/groups -type f | wc -l)
echo "   jellyfish-translator (${N} groups, ${THREADS} threads)"
for (( i=1; i<=N; i++ )); do
    process_group $i &
    pids+=($!)
    while [[ ${#pids[@]} -ge ${THREADS} ]]; do
            wait "${pids[0]}"
            unset "pids[0]"
            pids=("${pids[@]}")
    done
done
wait

elapsed_time=$(( SECONDS - start_time ))


# 3. Linker
for (( i=1; i<=N; i++ )); do
    $DIS output/bc/${PROJECT}.${i}.bc
done
echo "    jellyfish-link"
$LINK output/bc/*.ll -o output/${PROJECT}.ll
$AS output/${PROJECT}.ll
echo "    > output/${PROJECT}.bc"

echo FINISH! ${PROJECT} takes ${elapsed_time}s
