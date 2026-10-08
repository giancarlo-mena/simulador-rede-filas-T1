import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Evolucao do SimuladorFilaM4: simulacao por eventos discretos de uma rede de filas. */
public class SimuladorRedeFilas {
    static final long A = 1103515245L;
    static final long C = 12345L;
    static final long M = 1L << 31;

    enum TipoEvento { CHEGADA, SAIDA, PASSAGEM }

    static class Rota {
        final String destino;
        final double probabilidade;
        Rota(String destino, double probabilidade) {
            this.destino = destino;
            this.probabilidade = probabilidade;
        }
    }

    static class ConfigFila {
        final String nome;
        final int servidores;
        final Integer capacidade; // null representa capacidade ilimitada.
        final Double minChegada, maxChegada;
        final double minServico, maxServico;
        final List<Rota> rotas = new ArrayList<>();
        ConfigFila(String nome, int servidores, Integer capacidade, Double minChegada,
                   Double maxChegada, double minServico, double maxServico) {
            this.nome = nome;
            this.servidores = servidores;
            this.capacidade = capacidade;
            this.minChegada = minChegada;
            this.maxChegada = maxChegada;
            this.minServico = minServico;
            this.maxServico = maxServico;
        }
    }

    static class Modelo {
        final Map<String, ConfigFila> filas = new LinkedHashMap<>();
        final Map<String, Double> chegadas = new LinkedHashMap<>();
        final List<Long> sementes = new ArrayList<>();
        final List<Double> numeros = new ArrayList<>();
        int quantidade;
    }

    static class Fila {
        final ConfigFila config;
        int clientes, ocupados, maximoObservado;
        long perdas;
        final Map<Integer, Double> tempos = new TreeMap<>();
        Fila(ConfigFila config) { this.config = config; }
        void acumular(double intervalo) { tempos.merge(clientes, intervalo, Double::sum); }
        void receber() {
            if (config.capacidade != null && clientes >= config.capacidade) {
                perdas++;
            } else {
                clientes++;
                maximoObservado = Math.max(maximoObservado, clientes);
            }
        }
    }

    static class Evento {
        final double tempo;
        final long ordem;
        final TipoEvento tipo;
        final Fila origem, destino; // null representa o exterior.
        Evento(double tempo, long ordem, TipoEvento tipo, Fila origem, Fila destino) {
            this.tempo = tempo;
            this.ordem = ordem;
            this.tipo = tipo;
            this.origem = origem;
            this.destino = destino;
        }
    }

    static class Aleatorios {
        long anterior;
        final int limite;
        final List<Double> lista;
        int consumidos;
        Aleatorios(long semente, int limite) {
            this.anterior = Math.floorMod(semente, M);
            this.limite = limite;
            this.lista = null;
        }
        Aleatorios(List<Double> lista) {
            this.lista = lista;
            this.limite = lista.size();
        }
        boolean disponivel() { return consumidos < limite; }
        double proximo() {
            if (!disponivel()) throw new IllegalStateException("Limite de aleatorios atingido.");
            if (lista != null) return lista.get(consumidos++);
            anterior = (A * anterior + C) % M;
            consumidos++;
            return (double) anterior / M;
        }
        double uniforme(double minimo, double maximo) {
            return minimo + (maximo - minimo) * proximo();
        }
    }

    static class Simulacao {
        final Map<String, Fila> filas = new LinkedHashMap<>();
        final PriorityQueue<Evento> agenda = new PriorityQueue<>(
            Comparator.comparingDouble((Evento e) -> e.tempo).thenComparingLong(e -> e.ordem));
        final Aleatorios aleatorios;
        long ordem;
        double tempo;

        Simulacao(Modelo modelo, Aleatorios aleatorios) {
            this.aleatorios = aleatorios;
            modelo.filas.forEach((nome, config) -> filas.put(nome, new Fila(config)));
            modelo.chegadas.forEach((nome, instante) -> agendar(instante, TipoEvento.CHEGADA,
                                                                              null, filas.get(nome)));
        }
        void agendar(double instante, TipoEvento tipo, Fila origem, Fila destino) {
            if (!Double.isFinite(instante) || instante < tempo)
                throw new IllegalStateException("Tempo invalido no agendamento.");
            agenda.add(new Evento(instante, ordem++, tipo, origem, destino));
        }
        // A lista inclui a saida para o exterior como ultima possibilidade.
        Fila sortearDestino(Fila fila) {
            List<Rota> rotas = fila.config.rotas;
            if (rotas.size() == 1) return filas.get(rotas.get(0).destino);
            double sorteio = aleatorios.proximo();
            double soma = 0;
            for (Rota rota : rotas) {
                soma += rota.probabilidade;
                if (sorteio < soma) return filas.get(rota.destino);
            }
            // Protecao contra arredondamento de probabilidades em ponto flutuante.
            return filas.get(rotas.get(rotas.size() - 1).destino);
        }
        void iniciarAtendimentos(Fila fila) {
            while (aleatorios.disponivel() && fila.ocupados < fila.config.servidores
                   && fila.ocupados < fila.clientes) {
                Fila destino = sortearDestino(fila);
                if (!aleatorios.disponivel()) return;
                double duracao = aleatorios.uniforme(fila.config.minServico, fila.config.maxServico);
                fila.ocupados++;
                agendar(tempo + duracao, destino == null ? TipoEvento.SAIDA : TipoEvento.PASSAGEM,
                        fila, destino);
            }
        }
        void executar() {
            while (aleatorios.disponivel() && !agenda.isEmpty()) {
                Evento evento = agenda.remove();
                double intervalo = evento.tempo - tempo;
                for (Fila fila : filas.values()) fila.acumular(intervalo);
                tempo = evento.tempo;

                // A movimentacao instantanea e concluida antes de novos sorteios.
                // Assim, uma passagem nunca deixa um cliente perdido entre duas filas.
                if (evento.tipo == TipoEvento.CHEGADA) {
                    evento.destino.receber();
                    iniciarAtendimentos(evento.destino);
                    if (aleatorios.disponivel()) {
                        ConfigFila config = evento.destino.config;
                        double intervaloChegada = aleatorios.uniforme(config.minChegada, config.maxChegada);
                        agendar(tempo + intervaloChegada, TipoEvento.CHEGADA, null, evento.destino);
                    }
                } else {
                    evento.origem.clientes--;
                    evento.origem.ocupados--;
                    if (evento.destino != null) evento.destino.receber();
                    iniciarAtendimentos(evento.origem);
                    if (evento.destino != null && evento.destino != evento.origem)
                        iniciarAtendimentos(evento.destino);
                }
            }
        }
        String relatorio(String execucao) {
            StringBuilder texto = new StringBuilder();
            texto.append("SIMULACAO DE REDE DE FILAS\n").append(execucao).append('\n');
            texto.append("Gerador: GCL; A=1103515245; C=12345; M=2147483648\n");
            texto.append("Aleatorios consumidos: ").append(aleatorios.consumidos).append('\n');
            texto.append("Motivo da parada: ").append(aleatorios.disponivel() ? "agenda vazia" : "limite de aleatorios").append('\n');
            texto.append(String.format(Locale.ROOT, "Tempo global: %.9f min%n", tempo));
            texto.append("\n");
            for (Fila fila : filas.values()) {
                ConfigFila c = fila.config;
                texto.append(c.nome).append(" - G/G/").append(c.servidores);
                if (c.capacidade != null) texto.append('/').append(c.capacidade);
                texto.append('\n');
                texto.append("Estado;Tempo acumulado (min);Probabilidade (%)\n");
                int maximo = c.capacidade == null ? fila.maximoObservado : c.capacidade;
                for (int estado = 0; estado <= maximo; estado++) {
                    double acumulado = fila.tempos.getOrDefault(estado, 0.0);
                    texto.append(String.format(Locale.ROOT, "%d;%.9f;%.9f%n", estado, acumulado,
                                               tempo > 0 ? 100 * acumulado / tempo : 0));
                }
                if (c.capacidade == null)
                    texto.append("Estados acima do maximo observado: tempo e probabilidade iguais a zero nesta execucao.\n");
                texto.append("Clientes perdidos: ").append(fila.perdas).append('\n');
                texto.append("\n");
            }
            return texto.toString();
        }
    }

    static Map<?, ?> mapa(Object valor, String campo) {
        if (!(valor instanceof Map)) throw new IllegalArgumentException(campo + " deve ser um mapa.");
        return (Map<?, ?>) valor;
    }
    static List<?> lista(Object valor, String campo) {
        if (!(valor instanceof List)) throw new IllegalArgumentException(campo + " deve ser uma lista.");
        return (List<?>) valor;
    }
    static double numero(Object valor, String campo) {
        if (!(valor instanceof Number) || !Double.isFinite(((Number) valor).doubleValue()))
            throw new IllegalArgumentException(campo + " deve ser um numero finito.");
        return ((Number) valor).doubleValue();
    }
    static long inteiro(Object valor, String campo) {
        if (!(valor instanceof Byte || valor instanceof Short || valor instanceof Integer || valor instanceof Long))
            throw new IllegalArgumentException(campo + " deve ser um inteiro.");
        return ((Number) valor).longValue();
    }
    static int positivo(Object valor, String campo) {
        long n = inteiro(valor, campo);
        if (n <= 0 || n > Integer.MAX_VALUE) throw new IllegalArgumentException(campo + " deve ser positivo e caber em int.");
        return (int) n;
    }
    static void intervalo(double min, double max, String campo) {
        if (min <= 0 || max < min) throw new IllegalArgumentException(campo + ": exige 0 < minimo <= maximo.");
    }
    static void chaves(Map<?, ?> mapa, String campo, String... permitidas) {
        List<String> nomes = List.of(permitidas);
        for (Object chave : mapa.keySet())
            if (!(chave instanceof String) || !nomes.contains(chave))
                throw new IllegalArgumentException("Campo desconhecido em " + campo + ": " + chave);
    }
    static String nome(Object valor, String campo) {
        if (!(valor instanceof String) || ((String) valor).isBlank())
            throw new IllegalArgumentException(campo + " deve ser um nome nao vazio.");
        return (String) valor;
    }
    static Modelo carregar(Path arquivo) throws IOException {
        String texto = Files.readString(arquivo, StandardCharsets.UTF_8);
        // O marcador do simulador do professor identifica o modelo, nao uma classe Java.
        texto = texto.replaceFirst("(?m)^\\s*!PARAMETERS\\s*(?:#.*)?$", "");
        LoaderOptions opcoes = new LoaderOptions();
        opcoes.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(opcoes));
        Map<?, ?> raiz = mapa(yaml.load(texto), "modelo");
        chaves(raiz, "modelo", "arrivals", "queues", "network", "rndnumbers", "rndnumbersPerSeed", "seeds");
        Modelo modelo = new Modelo();
        Map<?, ?> filas = mapa(raiz.get("queues"), "queues");
        if (filas.isEmpty()) throw new IllegalArgumentException("Informe pelo menos uma fila.");
        for (Map.Entry<?, ?> item : filas.entrySet()) {
            String nome = nome(item.getKey(), "nome da fila");
            Map<?, ?> c = mapa(item.getValue(), nome);
            chaves(c, nome, "servers", "capacity", "minArrival", "maxArrival", "minService", "maxService");
            int servidores = positivo(c.get("servers"), nome + ".servers");
            Integer capacidade = c.containsKey("capacity") ? positivo(c.get("capacity"), nome + ".capacity") : null;
            Double minChegada = c.containsKey("minArrival") ? numero(c.get("minArrival"), nome + ".minArrival") : null;
            Double maxChegada = c.containsKey("maxArrival") ? numero(c.get("maxArrival"), nome + ".maxArrival") : null;
            if ((minChegada == null) != (maxChegada == null))
                throw new IllegalArgumentException(nome + ": informe os dois limites de chegada.");
            if (minChegada != null) intervalo(minChegada, maxChegada, nome + ".arrival");
            double minServico = numero(c.get("minService"), nome + ".minService");
            double maxServico = numero(c.get("maxService"), nome + ".maxService");
            intervalo(minServico, maxServico, nome + ".service");
            modelo.filas.put(nome, new ConfigFila(nome, servidores, capacidade, minChegada, maxChegada, minServico, maxServico));
        }
        if (raiz.containsKey("arrivals")) {
            for (Map.Entry<?, ?> item : mapa(raiz.get("arrivals"), "arrivals").entrySet()) {
                String nome = nome(item.getKey(), "arrivals");
                ConfigFila fila = modelo.filas.get(nome);
                if (fila == null || fila.minChegada == null)
                    throw new IllegalArgumentException("Chegada exige fila existente e intervalo: " + nome);
                double tempo = numero(item.getValue(), "arrivals." + nome);
                if (tempo < 0) throw new IllegalArgumentException("Primeira chegada nao pode ser negativa.");
                modelo.chegadas.put(nome, tempo);
            }
        }
        for (ConfigFila fila : modelo.filas.values())
            if (fila.minChegada != null && !modelo.chegadas.containsKey(fila.nome))
                throw new IllegalArgumentException("Informe a primeira chegada em arrivals para " + fila.nome);
        if (raiz.containsKey("network")) {
            for (Object item : lista(raiz.get("network"), "network")) {
                Map<?, ?> rota = mapa(item, "rota");
                chaves(rota, "rota", "source", "target", "probability");
                String origem = nome(rota.get("source"), "source");
                String destino = nome(rota.get("target"), "target");
                if (!modelo.filas.containsKey(origem) || !modelo.filas.containsKey(destino))
                    throw new IllegalArgumentException("Rota referencia fila inexistente.");
                double p = numero(rota.get("probability"), "probability");
                if (p < 0 || p > 1) throw new IllegalArgumentException("Probabilidade fora de [0, 1].");
                if (p > 0) modelo.filas.get(origem).rotas.add(new Rota(destino, p));
            }
        }
        for (ConfigFila fila : modelo.filas.values()) {
            double soma = fila.rotas.stream().mapToDouble(r -> r.probabilidade).sum();
            if (soma > 1 + 1e-12) throw new IllegalArgumentException("Soma das probabilidades maior que 1 em " + fila.nome);
            if (soma < 1 - 1e-12) fila.rotas.add(new Rota(null, 1 - soma));
        }
        if (raiz.containsKey("seeds")) {
            modelo.quantidade = positivo(raiz.get("rndnumbersPerSeed"), "rndnumbersPerSeed");
            for (Object semente : lista(raiz.get("seeds"), "seeds")) modelo.sementes.add(inteiro(semente, "seed"));
            if (modelo.sementes.isEmpty()) throw new IllegalArgumentException("seeds nao pode ser vazio.");
        } else {
            for (Object n : lista(raiz.get("rndnumbers"), "rndnumbers")) {
                double valor = numero(n, "rndnumbers");
                if (valor < 0 || valor >= 1) throw new IllegalArgumentException("Aleatorios devem estar em [0, 1).");
                modelo.numeros.add(valor);
            }
            if (modelo.numeros.isEmpty()) throw new IllegalArgumentException("rndnumbers nao pode ser vazio.");
        }
        return modelo;
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: java -cp \"build:lib/*\" SimuladorRedeFilas modelo.yml [resultado.txt]");
            System.exit(1);
        }
        try {
            Modelo modelo = carregar(Path.of(args[0]));
            StringBuilder resultados = new StringBuilder();
            if (modelo.sementes.isEmpty()) {
                Simulacao simulacao = new Simulacao(modelo, new Aleatorios(modelo.numeros));
                simulacao.executar();
                resultados.append(simulacao.relatorio("Lista explicita de aleatorios"));
            } else {
                for (long semente : modelo.sementes) {
                    Simulacao simulacao = new Simulacao(modelo, new Aleatorios(semente, modelo.quantidade));
                    simulacao.executar();
                    resultados.append(simulacao.relatorio("Semente: " + semente)).append('\n');
                }
            }
            if (args.length == 2) Files.writeString(Path.of(args[1]), resultados, StandardCharsets.UTF_8);
            System.out.print(resultados);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(1);
        }
    }
}
