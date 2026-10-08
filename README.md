# Simulador de rede de filas

Trabalho T1 de Simulação e Métodos Analíticos. O programa lê um arquivo YAML
e simula uma rede com qualquer quantidade de filas, servidores e rotas.

## Executar o trabalho

Requer **Java 17 ou superior**. Extraia o ZIP inteiro, mantendo a pasta `lib`
no mesmo diretório de `simulador.jar`. Não é necessário abrir uma IDE,
configurar dependências, instalar Maven ou compilar o código.

Abra o terminal na pasta do projeto e execute este mesmo comando no Windows,
macOS ou Linux:

```sh
java -jar simulador.jar modelos/avaliacao.yml resultados/avaliacao.txt
```

O resultado aparece no terminal e é salvo no arquivo indicado. O segundo
argumento é opcional; a pasta de destino precisa existir. O JAR localiza
automaticamente a biblioteca SnakeYAML em `lib/snakeyaml-2.3.jar`.
Para conferir a versão instalada do Java, execute `java -version`.

## Compilar o código-fonte se necessário

O código-fonte está em `SimuladorRedeFilas.java`. Para recompilá-lo, use
**JDK 17 ou superior** e execute:

```sh
javac -encoding UTF-8 -cp "lib/*" -d build SimuladorRedeFilas.java
```

Para executar as classes recompiladas no macOS ou Linux:

```sh
java -cp "build:lib/*" SimuladorRedeFilas modelos/avaliacao.yml resultados/avaliacao.txt
```

No Windows:

```powershell
java -cp "build;lib/*" SimuladorRedeFilas modelos/avaliacao.yml resultados/avaliacao.txt
```

A compilação acima gera a pasta `build`; ela não modifica o `simulador.jar`
fornecido. Após alterar o fonte, use os comandos de execução das classes
recompiladas. A biblioteca SnakeYAML 2.3 e sua licença Apache 2.0 estão em `lib`.

## Arquivo de entrada

O arquivo `modelos/avaliacao.yml` contém a rede da avaliação. Para simular outra
rede, altere ou crie um YAML com os mesmos campos:

- `queues`: filas com `servers`, `minService` e `maxService`.
- `capacity`: capacidade total, incluindo atendimento. Omitir para capacidade ilimitada.
- `minArrival` e `maxArrival`: limites entre chegadas externas, quando houver.
- `arrivals`: instante da primeira chegada externa em cada fila que a recebe.
- `network`: rotas com `source`, `target` e `probability`. O complemento da soma
  das probabilidades de cada origem representa a saída para o exterior.
- `seeds` e `rndnumbersPerSeed`: sementes e limite de sorteios por execução.
- `rndnumbers`: alternativa com lista explícita de números em [0, 1).
  Quando `seeds` existe, essa lista é ignorada.

O marcador `!PARAMETERS` é aceito, conforme o modelo fornecido na disciplina.
Tempos de chegada e serviço usam distribuição uniforme. Os limites devem ser
positivos, com mínimo menor ou igual ao máximo. São permitidas rotas de retorno,
inclusive para a própria fila, e várias filas com chegadas externas.

## Simulação e resultados

Todas as filas começam vazias. A agenda processa eventos de chegada, saída e
passagem em ordem de tempo; empates seguem a ordem de inserção. Antes de cada
evento, o tempo transcorrido é acumulado no estado atual de todas as filas.
O estado inclui clientes esperando e em atendimento. Quando o destino está
cheio, a perda é contabilizada nessa fila.

Ao iniciar um atendimento, sorteia-se primeiro a rota, se houver mais de uma
possibilidade, e depois o tempo de serviço. A próxima chegada externa é
agendada depois do atendimento. Nas passagens, os novos atendimentos são
agendados primeiro na origem e depois no destino.

O gerador congruente linear mantém os parâmetros do módulo 4:
`a = 1103515245`, `c = 12345` e `m = 2147483648`.
A configuração da avaliação usa semente **12345**, primeira chegada em
**2,0 minutos** e **100.000 aleatórios**. A simulação termina no instante em que
o último número é consumido, sem processar os eventos futuros da agenda.

O relatório apresenta o tempo global, os tempos e probabilidades de cada estado
e as perdas por fila. A probabilidade é o tempo no estado dividido pelo tempo
global. Estados não observados de uma fila ilimitada têm frequência empírica zero.

Resultado da configuração incluída: tempo global **50.730,857221172 minutos**
e perdas **0**, **5** e **11.549** nas filas Q1, Q2 e Q3.
