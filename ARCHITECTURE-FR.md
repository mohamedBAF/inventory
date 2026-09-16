# Rapport d'architecture — Cache Redis, `@Retryable` et verrous distribués

**Projet :** `inventory` (Spring Boot 4.1.1 / Spring Framework 7.0.9 / Java 21)
**Date :** 16 septembre 2026
**Objet :** document de référence expliquant tout ce qui a été ajouté au projet, et surtout *pourquoi*.

> Ce document explique les **concepts** et les **décisions**.
> `LEARNING.md` est son complément pratique : les commandes `curl` à exécuter, dans l'ordre.

---

## Table des matières

1. [Objectif et philosophie du projet](#1-objectif-et-philosophie-du-projet)
2. [La stack et ses pièges](#2-la-stack-et-ses-pièges)
3. [Architecture générale](#3-architecture-générale)
4. [Concept n°1 — Le cache Redis](#4-concept-n1--le-cache-redis)
5. [Concept n°2 — `@Retryable`](#5-concept-n2--retryable)
6. [Concept n°3 — Les verrous](#6-concept-n3--les-verrous)
7. [Le fil rouge : l'ordre des proxies](#7-le-fil-rouge--lordre-des-proxies)
8. [Gestion des erreurs](#8-gestion-des-erreurs)
9. [Inventaire des fichiers](#9-inventaire-des-fichiers)
10. [Limites assumées](#10-limites-assumées)

---

## 1. Objectif et philosophie du projet

Trois principes ont guidé chaque fichier écrit.

### 1.1 Montrer le bug avant la solution

Le projet contient du code **volontairement faux**, conservé à côté du code correct :

| Endpoint « faux » | Endpoint correct | Ce que la comparaison enseigne |
|---|---|---|
| `GET /api/products` | `GET /api/products/cached` | le coût réel d'un aller-retour base de données |
| `POST /api/stock/{id}/reserve-unsafe` | `POST /api/stock/{id}/reserve` | ce qu'est concrètement une *race condition* |
| `mode=unlocked` | `mode=redis-lock` | la survente (*oversell*), reproduite à la demande |

Une race condition que l'on a **vue se produire** vaut dix race conditions lues dans un article.
C'est pourquoi `StockService.reserveUnsafe()` contient un `sleepQuietly(20)` : il élargit
artificiellement la fenêtre de course pour que le bug soit **reproductible** même sur une machine
rapide.

### 1.2 Rendre l'invisible observable

Le cache et les verrous sont invisibles par nature : quand ils marchent, il ne se passe « rien ».
Le projet expose donc trois surfaces d'observation :

- **`AdminCacheController`** — les clés Redis réelles, leur TTL restant, les verrous actuellement détenus ;
- **les champs de réponse** — `attempts`, `cacheHit`, `ttlSeconds`, `oversold` sont *dans* le JSON ;
- **`logging.level.org.springframework.cache: TRACE`** — chaque hit, miss et éviction dans les logs.

### 1.3 Séparer ce qui doit être séparé

Le découpage `StockFacade` / `StockService` n'est pas cosmétique : il est imposé par le
fonctionnement des proxies Spring (voir [§7](#7-le-fil-rouge--lordre-des-proxies)). C'est la leçon
la plus importante du projet, et elle est structurelle, pas annotée.

---

## 2. La stack et ses pièges

Spring Boot 4 a changé plusieurs noms et plusieurs défauts. Chacun de ces points a été **vérifié
contre les JAR réellement présents** avant d'écrire une ligne de code — pas supposé depuis un
tutoriel écrit pour Boot 3.

### 2.1 Renommage des starters

```xml
<!-- Boot 3                        ->  Boot 4                            -->
<!-- spring-boot-starter-web       ->  spring-boot-starter-webmvc        -->
<!-- spring-boot-starter-aop       ->  spring-boot-starter-aspectj       -->
```

`spring-boot-starter-aop` **n'existe plus** en 4.1.1 (vérifié : HTTP 404 sur Maven Central). Le
starter AOP, nécessaire à notre aspect `@DistributedLock`, s'appelle désormais
`spring-boot-starter-aspectj`.

> ⚠️ **Piège XML** : un commentaire Maven ne doit **jamais** contenir la séquence `--`.
> Une ligne de séparation `<!-- ----- -->` rend le `pom.xml` non parsable. Cela a cassé le build
> une fois pendant le développement.

### 2.2 Jackson 3 — le piège le plus coûteux

Spring Boot 4 est passé à **Jackson 3** (`tools.jackson.*`). La dépendance `com.fasterxml.jackson`
n'est **plus présente** dans le classpath.

Conséquence directe sur Redis :

```java
// ❌ Compile parfaitement. Explose au RUNTIME en NoClassDefFoundError.
//    Cette classe existe encore dans le JAR, mais elle référence com.fasterxml, absent.
new GenericJackson2JsonRedisSerializer();

// ✅ La version Jackson 3, celle qu'il faut utiliser en Boot 4.
GenericJacksonJsonRedisSerializer.builder()...build();
```

Le `2` dans `Jackson2` désigne la **version de Jackson**, pas une v2 de la classe. Un tutoriel écrit
pour Boot 3 vous donnera systématiquement la mauvaise.

### 2.3 Retry natif : plus besoin de `spring-retry`

Depuis Spring Framework 7, le retry est **intégré à `spring-context`** :
`org.springframework.resilience.annotation.{Retryable, ConcurrencyLimit, EnableResilientMethods}`.

Le projet n'ajoute donc **aucune dépendance** pour `@Retryable`. Différences avec l'ancien
`spring-retry` que l'on trouve dans 99 % des tutoriels :

| `spring-retry` (ancien) | Spring Framework 7 (utilisé ici) |
|---|---|
| `maxAttempts = 4` — **inclut** le premier appel | `maxRetries = 3` — **après** le premier appel |
| `@Backoff(delay=…, multiplier=…)` | `delay`, `multiplier`, `maxDelay`, `jitter` en ligne |
| `include` / `exclude` | `includes` / `excludes` / `predicate` |
| *pas de budget de temps* | `timeout` — durée totale maximale |
| **`@Recover`** pour le repli | **rien — le repli s'écrit à la main** |

Cette dernière ligne a une conséquence architecturale réelle, traitée en [§5.4](#54-le-repli-écrit-à-la-main).

---

## 3. Architecture générale

### 3.1 Vue en couches

```
                         ┌──────────────────────────────┐
   HTTP                  │        Controllers           │
                         │  Product / Stock / Catalog   │
                         │  Restock / AdminCache        │
                         └──────────────┬───────────────┘
                                        │
              ┌─────────────────────────┴─────────────────────────┐
              │                                                   │
   ┌──────────▼───────────┐                          ┌────────────▼─────────────┐
   │  COUCHE RÉSILIENCE   │                          │   COUCHE MÉTIER / TX     │
   │  StockFacade         │   appel inter-beans      │   StockService           │
   │  RestockService      │ ───────────────────────► │   ProductService         │
   │  SupplierGateway     │  (OBLIGATOIRE : proxy)   │   CatalogService         │
   │                      │                          │                          │
   │  @DistributedLock    │                          │   @Transactional         │
   │  @Retryable          │                          │   @Cacheable/@CacheEvict │
   │  @ConcurrencyLimit   │                          │                          │
   └──────────┬───────────┘                          └────────────┬─────────────┘
              │                                                   │
       ┌──────▼──────┐                                   ┌────────▼────────┐
       │    REDIS    │                                   │   PostgreSQL    │
       │  cache+lock │                                   │  @Version, FOR  │
       │             │                                   │     UPDATE      │
       └─────────────┘                                   └─────────────────┘
```

### 3.2 Pourquoi deux couches et non une seule classe

Parce que **toutes** ces annotations (`@Cacheable`, `@Retryable`, `@Transactional`,
`@DistributedLock`) sont implémentées par des **proxies**. Un appel `this.methode()` à l'intérieur
d'un même bean **ne passe pas par le proxy** : l'annotation ne fait alors *strictement rien*, en
silence, sans le moindre avertissement.

Le découpage en deux beans rend cette contrainte **visible dans la structure du code** plutôt que
cachée dans un commentaire que personne ne lit.

### 3.3 Organisation des packages

```
com.yourname.inventory
├── common
│   ├── cache/CacheNames.java          constantes des noms de cache
│   ├── lock/                          annotation + service + aspect + exception
│   ├── retry/RetryAttemptTracker.java compteur de tentatives (ThreadLocal)
│   └── exception/GlobalExceptionHandler.java
├── config
│   ├── RedisConfig.java               sérialiseurs, template, script Lua
│   ├── CacheConfig.java               @EnableCaching, TTL, KeyGenerator, ErrorHandler
│   └── ResilienceConfig.java          @EnableResilientMethods
├── product/    CRUD + démonstration du cache
├── stock/      démonstration des verrous (4 stratégies + arène de course)
├── catalog/    cache avancé (sync, condition/unless, cache-aside manuel)
├── supplier/   démonstration du retry (API externe instable simulée)
└── admin/      introspection Redis
```

---

## 4. Concept n°1 — Le cache Redis

### 4.1 Le principe, en une phrase

Un cache échange de la **fraîcheur** contre de la **latence**. Toute la difficulté du sujet tient
dans cette phrase : la seule vraie question n'est jamais « comment mettre en cache ? » mais
**« combien de temps ai-je le droit de servir une donnée périmée ? »**

C'est pour cela que chaque cache du projet a un TTL **différent**, choisi en fonction du métier.

### 4.2 Configuration — `config/CacheConfig.java`

```java
@Configuration
@EnableCaching                                  // sans ça, les annotations sont des commentaires
public class CacheConfig implements CachingConfigurer {

    public static final String KEY_PREFIX = "inventory:cache:";
```

Trois éléments qu'un cache Redis de production doit obligatoirement avoir, et qui sont tous câblés
ici.

#### a) Un TTL **par cache**

| Cache | TTL | Justification métier |
|---|---|---|
| `product` | 10 min | change rarement, et chaque écriture l'évince explicitement |
| `productPage` | 1 min | invalidée par *n'importe quelle* création/suppression → filet de sécurité court |
| `productSearch` | 2 min | résultat dérivé, tolérant |
| `stockLevel` | **30 s** | le stock, c'est de l'argent — et `disableCachingNullValues()` |
| `catalogReport` | 2 min | agrégat coûteux |
| `supplierQuote` | 45 s | un prix fournisseur périmé est un risque commercial |

Un TTL global unique est **toujours** faux : un produit change une fois par mois, un niveau de stock
change chaque seconde. Et une entrée **sans TTL** vit éternellement — c'est la cause n°1 de données
périmées en production.

#### b) Un préfixe de clés

```java
.computePrefixWith(cacheName -> KEY_PREFIX + cacheName + "::")
```

Redis est un **espace de noms unique et plat**. Sans préfixe, vos clés se mélangent à celles des
autres applications qui partagent l'instance. Avec, vous pouvez inspecter (`SCAN inventory:cache:*`)
ou vider une application entière d'un seul motif.

#### c) `transactionAware()` — subtil mais critique

```java
.transactionAware()
```

Les écritures de cache sont **différées jusqu'au COMMIT** de la transaction englobante.

Sans cette option, le scénario suivant se produit :

```
BEGIN TRANSACTION
  update(produit)         → @CachePut écrit la nouvelle valeur dans Redis  ← IMMÉDIATEMENT
  ... une erreur survient
ROLLBACK                  → la base revient en arrière, PAS Redis
```

Résultat : **le cache décrit une donnée qui n'a jamais existé**, et ce jusqu'à l'expiration du TTL.
C'est un des bugs les plus difficiles à diagnostiquer qui soient, parce que la base est correcte et
que le code semble correct.

#### d) Le `CacheErrorHandler` — *fail-open*

```java
@Override
public CacheErrorHandler errorHandler() {
    return new CacheErrorHandler() {
        @Override public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
            log.warn("Cache GET failed … falling back to the database: {}", ex.getMessage());
        }
        …
    };
}
```

**Par défaut, si Redis tombe, toute méthode annotée lève une exception.** Vous avez alors ajouté un
cache pour améliorer les performances, et créé au passage un **nouveau point de défaillance unique**.

Avec ce handler : Redis tombe → un `WARN` dans les logs → la requête est servie depuis PostgreSQL.
Plus lent, mais vivant.

Test à faire, c'est la démonstration la plus parlante du projet :

```bash
docker compose stop redis
curl -i localhost:8080/api/products/{id}      # 200 OK + WARN dans les logs
# Commentez le bean errorHandler → la même requête renvoie 500.
```

**Nuance importante**, écrite dans le code : avaler une erreur d'**éviction** est plus dangereux
qu'avaler une erreur de lecture, car le cache reste alors **périmé** jusqu'au TTL. Pour un cache
critique, préférez un TTL court à un échec silencieux.

#### e) Le `KeyGenerator` lisible

`SimpleKeyGenerator` (le défaut de Spring) produit `SimpleKey [a, b]`, illisible dans `redis-cli`.
Le nôtre produit `ProductService.findById:3f2a-…` : **l'espace de clés Redis se documente lui-même**.

### 4.3 Les sérialiseurs — `config/RedisConfig.java`

```java
@Bean
public PolymorphicTypeValidator redisTypeValidator() {
    return BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("com.yourname.inventory.")
        .allowIfSubType("java.util.")
        .allowIfSubType("java.time.")
        .allowIfSubType("java.math.")
        .allowIfSubType("java.lang.")
        .build();
}
```

**Ce n'est pas une précaution théorique : c'est une faille d'exécution de code à distance.**

Pour désérialiser un objet polymorphe, Jackson écrit le nom de la classe dans le JSON (`@class`).
À la relecture, il **instancie la classe indiquée**. Si un attaquant peut écrire dans votre Redis
(instance partagée, réseau mal cloisonné, autre application compromise), il choisit la classe que
votre JVM va instancier. Certaines classes de bibliothèques courantes exécutent du code dans leur
constructeur ou leurs *setters* — c'est la famille de failles des « gadget chains ».

La liste blanche ci-dessus limite la désérialisation à des packages connus. **Sans elle, ne jamais
activer le typage par défaut.**

### 4.4 Les patterns de cache implémentés

#### Pattern 1 — `@Cacheable` avec un **DTO**, jamais une `Page`

```java
@Cacheable(cacheNames = CacheNames.PRODUCT_PAGE,
           key = "'p' + #pageable.pageNumber + ':s' + #pageable.pageSize")
public ProductPageResponse findAllCached(Pageable pageable) { … }
```

`PageImpl` **ne doit jamais être mise en cache** : pas de constructeur par défaut, pas de forme
sérialisée stable entre versions de Spring. Vous obtenez une erreur de désérialisation au prochain
déploiement, sur des données déjà en cache. On met toujours en cache **son propre DTO**.

Noter aussi la clé : elle contient le numéro et la taille de page, **et rien d'autre**. Mettre
l'objet `Pageable` entier dans la clé donne une clé instable (`toString()` inclut le tri, les
sous-objets…).

#### Pattern 2 — L'éviction combinée : `@Caching`

```java
@Caching(
    put    = { @CachePut(cacheNames = PRODUCT, key = "#id") },
    evict  = { @CacheEvict(cacheNames = PRODUCT_PAGE,   allEntries = true),
               @CacheEvict(cacheNames = PRODUCT_SEARCH, allEntries = true),
               @CacheEvict(cacheNames = CATALOG_REPORT, allEntries = true) })
public ProductResponse update(UUID id, UpdateProductRequest req) { … }
```

Logique métier de cette combinaison :

- l'entrée **unitaire** est *rafraîchie* (`@CachePut`) — on connaît la nouvelle valeur, autant la stocker ;
- toutes les vues **dérivées** (pages, recherches, rapports) sont *vidées* (`allEntries = true`) — on
  ne sait pas lesquelles contenaient ce produit, donc on les invalide toutes.

Pour `delete`, on ajoute `beforeInvocation = true` : l'éviction a lieu **avant** l'exécution, donc
elle se produit même si la suppression échoue. Sur une suppression, mieux vaut un cache vidé pour rien
qu'un cache qui garde un produit supprimé.

#### Pattern 3 — La pénétration de cache (mettre `null` en cache)

```java
@Cacheable(cacheNames = PRODUCT, key = "'sku:' + #sku")   // les null SONT mis en cache
public ProductResponse findBySkuNullable(String sku) { … }
```

**Le problème :** si les clés absentes ne sont pas mises en cache, n'importe qui peut contourner
intégralement votre cache en demandant en boucle des identifiants inexistants. Chaque requête
traverse jusqu'à la base. C'est un vecteur de déni de service trivial.

**La solution :** mettre le `null` en cache (activé globalement, et désactivé explicitement pour
`stockLevel` où un stock inconnu ne doit jamais être mémorisé).

#### Pattern 4 — La ruée (*cache stampede*) : `sync = true`

```java
@Cacheable(cacheNames = CATALOG_REPORT, key = "'full-report'", sync = true)
public CatalogReport fullReport() { /* ~1,5 s de calcul */ }
```

**Le problème :** une clé « chaude » expire. Au même instant, 200 requêtes constatent le *miss* et
lancent **toutes** le calcul coûteux. La base, qui tenait la charge une seconde plus tôt, s'écroule.
C'est le *thundering herd*, et c'est la cause classique d'incidents « au bout de N minutes pile ».

**La solution :** `sync = true` — un seul thread calcule, les autres attendent son résultat.

**Limite honnête, écrite dans le code :** `sync` verrouille **par JVM**, pas par cluster. Avec dix
instances vous obtenez dix calculs au lieu de deux cents. Pour un *single-flight* réellement
distribué, il faut le verrou du [§6.4](#64-stratégie-c--le-verrou-distribué-redis).

#### Pattern 5 — `condition` et `unless`

```java
@Cacheable(cacheNames = PRODUCT_SEARCH,
           condition = "#term.length() >= 3",          // AVANT l'exécution, sur les arguments
           unless    = "#result.items().isEmpty()")    // APRÈS l'exécution, voit #result
public SearchResult search(String term) { … }
```

La distinction est constamment confondue :

- **`condition`** est évaluée **avant** l'appel. Elle peut empêcher la lecture *et* l'écriture du cache.
  Elle n'a accès qu'aux **arguments**.
- **`unless`** est évaluée **après** l'appel. Elle n'empêche que l'**écriture**. Elle a accès à `#result`.

Ici : on ne met pas en cache les recherches trop courtes (trop nombreuses, peu sélectives), ni les
résultats vides (ils vont probablement changer, et ils ne coûtent rien à recalculer).

#### Pattern 6 — Le *cache-aside* manuel

`CatalogService.topProducts()` fait **à la main** ce que `@Cacheable` fait pour vous :

```
1. GET clé          → trouvé ?  on renvoie (cacheHit = true)
2. sinon : calculer
3. SET clé, valeur, TTL
4. renvoyer (cacheHit = false)
```

Deux raisons de l'écrire une fois soi-même :

1. **Comprendre** ce que l'annotation masque.
2. **Contrôler** ce que l'annotation ne permet pas — ici, renvoyer au client le *statut* du cache
   (`cacheHit`, `ttlSeconds`, `elapsedMs`), impossible en déclaratif.

Et surtout, le **jitter de TTL** :

```java
Duration.ofSeconds(120 + ThreadLocalRandom.current().nextInt(30))
```

**Le problème :** si mille clés sont écrites pendant le même redémarrage avec le même TTL, elles
expirent **toutes à la même seconde**. Vous avez programmé une ruée pour dans deux minutes.
**La solution :** randomiser le TTL pour étaler les expirations.

### 4.5 La conception des clés

`RestockService.quote(String sku, int failurePercent)` est mise en cache sur **`#sku` seul** :

```java
@Cacheable(cacheNames = SUPPLIER_QUOTE, key = "#sku")
```

Une clé doit contenir **tout ce qui identifie la donnée, et rien d'autre**. `failurePercent` est un
paramètre de test, pas une caractéristique du devis. L'inclure (ce que fait le `KeyGenerator` par
défaut !) créerait une entrée par valeur de `failurePercent` — et c'est **la raison la plus fréquente
d'un cache qui « ne fait jamais de hit »**.

---

## 5. Concept n°2 — `@Retryable`

### 5.1 La question préalable : qu'est-ce qui mérite d'être réessayé ?

Un retry ne répare qu'une chose : une **panne transitoire**. Réessayer autre chose, c'est réessayer
un bug — plus lentement, et en amplifiant la charge sur un système déjà en difficulté.

| ✅ À réessayer | ❌ À ne jamais réessayer |
|---|---|
| `503 Service Unavailable`, `429 Too Many Requests` | `400`, `404`, `422` — la tentative suivante échouera identiquement |
| Connexion coupée, *read timeout* | `401`, `403` — corrigez les identifiants, pas le nombre de tentatives |
| Interblocage (*deadlock*) et *lock timeout* base | Toute erreur de validation métier |
| Conflit de verrouillage optimiste | |
| `GET` / `PUT` / `DELETE` (idempotents) | **`POST` non idempotent sans clé d'idempotence** |

Cette dernière ligne est la plus dangereuse. Réessayer un `POST /payments` dont la réponse s'est
perdue en route, c'est **débiter le client deux fois**. Le retry est alors correct uniquement si le
serveur déduplique via une clé d'idempotence.

C'est pourquoi **chaque `@Retryable` du projet déclare explicitement `includes = …`**. Jamais de
retry universel.

### 5.2 Le backoff exponentiel et le jitter

```java
@Retryable(includes = SupplierUnavailableException.class,
           maxRetries = 3,
           delay      = 200,     // 1er délai : 200 ms
           multiplier = 2.0,     // puis 400, puis 800
           jitter     = 50,      // ± 50 ms aléatoires sur chaque délai
           maxDelay   = 2_000)   // plafond de sécurité
public SupplierQuote fetchQuote(String sku, int failurePercent) { … }
```

**Pourquoi exponentiel ?** Si le service distant est saturé, réessayer immédiatement et à cadence
constante *ajoute* de la charge à un système déjà à genoux. L'espacement croissant lui laisse une
chance de se rétablir.

**Pourquoi `jitter` ?** Ce n'est pas un détail cosmétique. Sans jitter :

```
t=0      1000 clients échouent en même temps (le service redémarre)
t=200ms  1000 clients réessaient EXACTEMENT en même temps
t=600ms  1000 clients réessaient EXACTEMENT en même temps
```

Le service qui se rétablissait est **tué par ses propres clients**, de façon parfaitement
synchronisée. Le jitter disperse les tentatives. C'est un problème réel, à l'origine de pannes en
cascade documentées chez la plupart des grands hébergeurs.

**Pourquoi `maxDelay` ?** Sans plafond, `multiplier = 2.0` sur dix tentatives donne un dernier délai
de plus de trois minutes. Personne n'attend trois minutes.

### 5.3 Le budget de temps — souvent meilleur qu'un compteur

```java
@Retryable(includes = SupplierUnavailableException.class,
           maxRetries = 10, delay = 150, multiplier = 1.5,
           timeout = 2_000)          // 2 s AU TOTAL, toutes tentatives confondues
public SupplierQuote fetchQuoteWithinBudget(String sku, int failurePercent) { … }
```

Quand un utilisateur attend derrière une requête HTTP, **un nombre de tentatives ne dit rien** :
4 tentatives peuvent prendre 300 ms comme 40 secondes, selon la latence du service distant.
Un budget de temps est une garantie que l'on peut afficher dans un contrat de service.

Règle pratique : **`timeout` quand quelqu'un attend, `maxRetries` pour un traitement par lot.**

### 5.4 Le repli écrit à la main

Spring Framework 7 n'a **pas** d'équivalent de `@Recover`. Quand toutes les tentatives échouent,
l'exception remonte telle quelle. Le repli s'écrit donc explicitement — et c'est très bien ainsi,
car il force à **décider** ce qu'est une réponse dégradée acceptable :

```java
@DistributedLock(key = "'restock:' + #productId", waitTimeMs = 1_000, leaseTimeMs = 15_000)
public RestockResult restock(UUID productId, int quantity, int failurePercent) {
    try {
        SupplierQuote quote = supplierGateway.fetchQuote(product.getSku(), failurePercent);
        stockFacade.adjustStock(productId, quantity, "restock from " + quote.supplier());
        return new RestockResult(…, /* ordered */ true, /* degraded */ false, …);

    } catch (SupplierUnavailableException ex) {
        // Toutes les tentatives sont épuisées. On dégrade au lieu de renvoyer un 500.
        return new RestockResult(…, /* ordered */ false, /* degraded */ true,
                "Fournisseur injoignable ; demande mise en file pour traitement manuel.");
    }
}
```

**Dégrader est une décision de conception, pas une astuce technique :**

- un endpoint de **devis** peut renvoyer le prix de la veille, marqué *périmé* ;
- un endpoint de **réapprovisionnement** peut mettre la demande en file (ce que fait ce code) ;
- un endpoint de **paiement** ne doit **jamais** inventer un repli — il doit échouer bruyamment.

Le troisième cas est celui que l'on oublie. Un repli silencieux sur une opération financière, c'est
une perte d'argent invisible.

### 5.5 Cache et retry ensemble : l'ordre compte

```
Controller → RestockService.quote()      @Cacheable      ← CACHE À L'EXTÉRIEUR
                     ↓  (appel inter-beans)
             SupplierGateway.fetchQuote() @Retryable     ← RETRY À L'INTÉRIEUR
```

**Cache à l'extérieur, retry à l'intérieur.** Un *hit* coûte alors zéro appel réseau **et zéro
tentative**. Dans l'ordre inverse — retry à l'extérieur, cache à l'intérieur — on réessaierait une
méthode qui ne peut renvoyer que la valeur déjà en cache : des tentatives rigoureusement inutiles.

Démonstration :

```bash
curl "…/quote/WIDGET-1/cached?failurePercent=0"     # succès, mis en cache
curl "…/quote/WIDGET-1/cached?failurePercent=100"   # 200 OK — la méthode ne s'exécute JAMAIS
```

### 5.6 `@ConcurrencyLimit` — la cloison étanche (*bulkhead*)

```java
@ConcurrencyLimit(2)
public String expensiveRecount(UUID productId) { … }
```

Au plus deux threads à l'intérieur simultanément ; les autres attendent.

**Le problème traité :** une dépendance lente qui n'échoue pas *franchement* est plus dangereuse
qu'une dépendance en panne. Les threads s'y accumulent, le pool applicatif se vide, et **toute
l'application** devient indisponible — y compris les endpoints qui n'ont rien à voir. C'est le
principe des cloisons d'un navire : limiter les dégâts à un compartiment.

### 5.7 `RetryAttemptTracker`

Petit utilitaire à base de `ThreadLocal`, qui permet de renvoyer au client le champ `attempts`.
Il fonctionne parce que **les tentatives de Spring s'exécutent sur le même thread** que l'appel
initial. C'est aussi une bonne piqûre de rappel : le retry est **synchrone et bloquant**, il occupe
votre thread pendant toute la durée du backoff.

---

## 6. Concept n°3 — Les verrous

### 6.1 Le problème, en trois lignes

```java
int stock = produit.getStockQty();     // lecture   : 10
if (stock >= quantite) {               // décision  : ok
    produit.setStockQty(stock - quantite);  // écriture : 9
}
```

Ce cycle **lire → décider → écrire** n'est pas atomique. Vingt threads lisent `10`, vingt décident
qu'il y a du stock, vingt écrivent `9`. Vous avez vendu 20 unités en n'en possédant que 10.

L'endpoint `POST /api/stock/demo/race` reproduit exactement cela, avec 20 threads virtuels Java 21
libérés simultanément par un `CountDownLatch` (le « coup de pistolet » du départ), et renvoie le
champ `oversold`.

### 6.2 Stratégie A — Verrou pessimiste en base

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
@Query("SELECT p FROM Product p WHERE p.id = :id")
Optional<Product> findByIdForUpdate(@Param("id") UUID id);
```

Traduit en `SELECT … FOR UPDATE`. PostgreSQL met les écrivains en file **sur la ligne elle-même**.
La correction n'est pas discutable : c'est la base de données qui l'assure.

| Avantages | Inconvénients |
|---|---|
| Correction garantie par le SGBD | Ne dépasse pas les limites d'**une seule** base |
| Simple à comprendre | Mobilise une connexion pendant toute la section critique |
| Pas de code de retry | Risque d'interblocage si l'ordre de verrouillage varie |

⚠️ **Le `lock.timeout` de 5 s n'est pas optionnel.** Sans délai maximal, un interblocage n'est pas
une erreur : c'est un **gel**. Un verrou sans timeout est un incident de production qui attend son
heure.

### 6.3 Stratégie B — Verrouillage optimiste (`@Version` + retry)

```java
@Entity
public class Product {
    @Version
    private long version;      // Hibernate l'incrémente à chaque UPDATE
}
```

Hibernate ajoute `WHERE id = ? AND version = ?` à chaque mise à jour. Si zéro ligne est modifiée,
c'est que quelqu'un d'autre est passé avant → `ObjectOptimisticLockingFailureException`.

On ne verrouille rien : **on détecte le conflit et on rejoue**.

```java
@Retryable(includes = { OptimisticLockingFailureException.class, CannotAcquireLockException.class },
           maxRetries = 4, delay = 50, multiplier = 2.0, jitter = 25, maxDelay = 500)
public StockOperationResponse adjustStock(UUID productId, int delta, String reason) {
    return stockService.applyDeltaOptimistic(productId, delta, reason);
}
```

**Deux détails qui font toute la différence :**

1. **`@Transactional(propagation = Propagation.REQUIRES_NEW)`** sur `applyDeltaOptimistic`.
   Chaque tentative obtient une **transaction neuve**. Réessayer à l'intérieur d'une transaction déjà
   marquée *rollback-only* ne fait que reproduire le même échec quatre fois de plus — et c'est une
   erreur extrêmement courante.

2. **Le retry doit être *au-dessus* de la transaction**, jamais à l'intérieur. D'où, à nouveau, la
   séparation `StockFacade` (retry) / `StockService` (transaction).

**Quand l'utiliser :** quand les conflits sont **rares**. Rien ne bloque, donc le cas nominal est
gratuit. Si le champ `attempts` de la réponse atteint régulièrement la limite, la ligne est trop
sollicitée pour cette stratégie : passez en pessimiste ou en verrou distribué.

### 6.4 Stratégie C — Le verrou distribué Redis

C'est le seul verrou que la base de données ne peut pas fournir, car il protège **une section
critique**, pas une ligne — et il fonctionne **entre plusieurs instances** de l'application.

#### L'acquisition : une seule commande atomique

```
SET inventory:lock:stock:<id> <token-aléatoire> NX PX <bail-ms>
```

- `NX` — uniquement si la clé n'existe pas → **exclusion mutuelle**
- `PX` — avec une durée de vie en millisecondes → **récupération automatique en cas de crash**

```java
Boolean acquired = redis.opsForValue().setIfAbsent(KEY_PREFIX + key, token, lease);
```

⚠️ **Le bug classique** consiste à écrire cela en deux commandes (`SETNX` puis `EXPIRE`). Si le
processus meurt entre les deux, la clé **n'a pas de TTL** et le verrou n'expire **jamais** :
interblocage permanent de tout le cluster. `setIfAbsent(clé, valeur, ttl)` correspond à la forme
atomique en une commande.

#### La libération : un script Lua, jamais un `DEL`

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

**Pourquoi ne pas faire un simple `DEL` ?** Parce que `DEL` supprime le verrou **de celui qui le
détient maintenant**. Scénario :

```
t=0      A prend le verrou, bail = 10 s
t=11 s   le travail de A n'est pas fini, mais le bail a EXPIRÉ
t=11 s   B prend le verrou (légitimement — il est libre)
t=12 s   A termine et fait DEL → A vient de supprimer LE VERROU DE B
t=12 s   C prend le verrou → B et C sont dans la section critique ENSEMBLE
```

Le jeton aléatoire, comparé et supprimé **atomiquement** par Lua, empêche exactement cela.

Et le `release()` qui renvoie `false` n'est pas du bruit : il signifie que **le bail a expiré pendant
le travail**, donc qu'un autre worker a peut-être été dans la section critique en même temps. D'où le
`log.warn` explicite.

#### Le bail (*lease*) : le compromis fondamental

Le TTL est ce qui **guérit les interblocages**. Si la JVM qui détient le verrou est tuée, aucun bloc
`finally` ne s'exécute jamais ; seule l'expiration Redis permet au système de se rétablir seul.

Mais c'est un arbitrage réel, dans les deux sens :

| Bail trop court | Bail trop long |
|---|---|
| On perd le verrou **en plein travail** | Un crash bloque tout le monde pendant toute sa durée |
| → d'où la vérification du jeton | → d'où des bails courts et des sections critiques courtes |

#### L'attente : spin-wait avec jitter

```java
Thread.sleep(ThreadLocalRandom.current().nextLong(50, 150));
```

Même raison qu'au [§5.2](#52-le-backoff-exponentiel-et-le-jitter) : avec une pause fixe, N candidats
se réveillent **en cadence** et martèlent Redis ensemble à chaque cycle.

À noter : c'est une attente **active**, qui consomme un thread. C'est acceptable ici parce que les
temps d'attente sont courts et que Java 21 rend les threads virtuels quasi gratuits.

#### L'annotation et l'aspect

```java
@DistributedLock(key = "'stock:' + #productId", waitTimeMs = 3_000, leaseTimeMs = 5_000)
public StockOperationResponse reserveWithDistributedLock(UUID productId, int quantity, String ref)
```

`waitTimeMs = 0` (utilisé par `runExclusiveAudit`) signifie **échec immédiat** : le second appelant
reçoit un `409 LOCK_BUSY` sans attendre. Pour un rapport que personne n'attend, *échouer vite* est
préférable à *faire la queue*.

L'aspect évalue la clé SpEL contre les arguments réels, en exposant **deux formes** de variable :

```java
context.setVariable(names[i], args[i]);   // #productId — nécessite la compilation avec -parameters
context.setVariable("p" + i, args[i]);    // #p0        — fonctionne toujours
```

Et la libération est **toujours** dans un `finally`. Une exception ne doit pas laisser le verrou
détenu jusqu'à la fin de son bail, ce qui bloquerait toutes les requêtes sur cette clé pendant
plusieurs secondes.

#### Limites assumées de cette implémentation

Écrites explicitement dans la Javadoc de `RedisLockService`, parce qu'un verrou distribué dont on
ignore les limites est plus dangereux que pas de verrou du tout :

1. **Non réentrant** — le même thread qui reprend le même verrou s'interbloque avec lui-même.
2. **Pas de *watchdog*** — aucun mécanisme ne prolonge le bail si le travail dure plus longtemps
   que prévu (Redisson le fait).
3. **Sémantique mono-nœud** — avec de la réplication Redis, un basculement peut perdre un verrou qui
   venait d'être écrit. C'est le problème que l'algorithme *Redlock* tente d'adresser.

**En production : utilisez Redisson ou `RedisLockRegistry` de Spring Integration.** Cette classe
existe pour que vous **voyiez** ce que ces bibliothèques font à votre place.

### 6.5 Ordonner les verrous pour éviter l'interblocage

Un transfert verrouille **deux** produits. Si le thread A prend `X` puis `Y` pendant que le thread B
prend `Y` puis `X`, les deux s'attendent mutuellement **pour toujours**.

```java
List<UUID> ordered = Stream.of(fromId, toId).sorted().toList();   // ordre déterministe
```

En triant les identifiants, **tous les threads du système prennent les verrous dans le même ordre**,
et un cycle devient mathématiquement impossible. La même règle s'applique aux lignes en base.

C'est la prévention d'interblocage la moins chère qui existe : un `sorted()`.

### 6.6 Tableau comparatif

| | Verrou pessimiste | Verrou optimiste | Verrou distribué |
|---|---|---|---|
| **Où** | PostgreSQL | PostgreSQL (`@Version`) | Redis |
| **Bloque ?** | Oui | Non | Oui |
| **Multi-instances** | Oui (même base) | Oui (même base) | **Oui, indépendant de la base** |
| **Protège** | une ligne | une ligne | **une section critique quelconque** |
| **Coût nominal** | connexion mobilisée | quasi nul | 2 allers-retours Redis |
| **Sous forte contention** | file d'attente | beaucoup de retries | file d'attente |
| **Panne possible** | interblocage | épuisement des retries | bail expiré en plein travail |
| **Bon pour** | tout est dans une base | conflits rares | tâches planifiées, API externes, multi-service |

---

## 7. Le fil rouge : l'ordre des proxies

C'est le point qui relie les trois concepts, et de loin le plus sous-estimé.

### 7.1 L'ordre d'imbrication

```java
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 100)     // LÉGÈREMENT avant la transaction
public class DistributedLockAspect { … }
```

L'advisor transactionnel de Spring s'exécute à `LOWEST_PRECEDENCE`. En donnant à notre aspect un
nombre **inférieur**, on garantit :

```
acquérir le verrou → BEGIN tx → corps de la méthode → COMMIT → libérer le verrou
```

**Si l'ordre était inversé** (verrou libéré *à l'intérieur* de la transaction, donc avant le
`COMMIT`), une autre requête prendrait le verrou et lirait des lignes **non encore validées**. Elle
travaillerait sur des données périmées : exactement la mise à jour perdue que le verrou était censé
empêcher. Le verrou serait présent, correctement écrit, et totalement inutile.

### 7.2 L'auto-invocation — le piège universel

```java
@Service
public class MonService {

    public void point_entrée() {
        this.methodeCachee();   // ❌ le proxy est CONTOURNÉ — aucun cache, aucun log, aucune erreur
    }

    @Cacheable("x")
    public Object methodeCachee() { … }
}
```

Cela vaut **pour toutes** les annotations basées sur un proxy : `@Cacheable`, `@Transactional`,
`@Retryable`, `@Async`, `@DistributedLock`. L'annotation devient **décorative**, sans le moindre
message d'erreur — c'est ce qui la rend si difficile à repérer.

**Trois conditions pour qu'une de ces annotations fonctionne :**

1. la méthode est **`public`** ;
2. elle est appelée **depuis un autre bean**, via l'injection Spring ;
3. la fonctionnalité est **activée** (`@EnableCaching`, `@EnableResilientMethods`…).

Le projet respecte cette contrainte **par construction** :

| Bean « extérieur » (proxifié) | Bean « intérieur » | Frontière |
|---|---|---|
| `StockFacade` (verrous, retry, bulkhead) | `StockService` (transactions, cache) | appel inter-beans |
| `RestockService` (cache, verrou, repli) | `SupplierGateway` (retry) | appel inter-beans |

---

## 8. Gestion des erreurs

`common/exception/GlobalExceptionHandler.java` a été étendu :

| Exception | Statut | Code |
|---|---|---|
| `LockAcquisitionException` | **409** | `LOCK_BUSY` |
| `OptimisticLockingFailureException` | **409** | `CONCURRENT_MODIFICATION` |
| `CannotAcquireLockException` | **409** | `ROW_LOCK_TIMEOUT` |
| `SupplierUnavailableException` | **503** | `SUPPLIER_UNAVAILABLE` |

**Le principe : un échec de concurrence est un `409`, jamais un `500`.**

Cette distinction n'est pas de la cosmétique HTTP, elle porte une information exploitable :

- **500** signifie « *mon* bug, ne réessayez pas » → un client correct abandonne ;
- **409** signifie « transitoire, réessayer est sans danger » → un client correct (ou un `@Retryable`
  en amont) rejoue automatiquement.

C'est littéralement la différence entre un client qui se rétablit tout seul et un client qui renonce.

---

## 9. Inventaire des fichiers

### Créés

| Fichier | Concept |
|---|---|
| `config/RedisConfig.java` | sérialiseurs Jackson 3, `PolymorphicTypeValidator`, `RedisTemplate`, script Lua |
| `config/CacheConfig.java` | `@EnableCaching`, TTL par cache, préfixe, `transactionAware`, `KeyGenerator`, `CacheErrorHandler` |
| `config/ResilienceConfig.java` | `@EnableResilientMethods` |
| `common/cache/CacheNames.java` | noms de caches en constantes |
| `common/lock/DistributedLock.java` | l'annotation (SpEL, `waitTimeMs`, `leaseTimeMs`, `throwOnFailure`) |
| `common/lock/RedisLockService.java` | `SET NX PX`, libération Lua, `runLocked`, `activeLocks` (via `SCAN`) |
| `common/lock/DistributedLockAspect.java` | l'aspect `@Around` et son `@Order` |
| `common/lock/LockAcquisitionException.java` | → 409 |
| `common/retry/RetryAttemptTracker.java` | compteur de tentatives `ThreadLocal` |
| `stock/StockService.java` | cœur transactionnel : 4 stratégies de réservation |
| `stock/StockFacade.java` | couche résilience : `@DistributedLock`, `@Retryable`, `@ConcurrencyLimit` |
| `stock/StockRaceDemoService.java` | arène de course, threads virtuels + `CountDownLatch` |
| `stock/StockController.java` + `stock/dto/*` | 9 endpoints de démonstration |
| `catalog/CatalogService.java` | `sync = true`, `condition`/`unless`, cache-aside manuel + jitter |
| `catalog/CatalogController.java` + `catalog/dto/*` | 4 endpoints |
| `supplier/SupplierGateway.java` | `@Retryable` : backoff, jitter, budget de temps |
| `supplier/RestockService.java` | repli manuel + cache-extérieur/retry-intérieur + verrou |
| `supplier/RestockController.java` + `supplier/dto/*` | 4 endpoints pilotés par `failurePercent` |
| `admin/AdminCacheController.java` | introspection : clés, TTL, éviction, verrous actifs |
| `docker-compose.yml` | PostgreSQL 17 + Redis 7 avec *healthchecks* |
| `LEARNING.md` | le parcours pratique, endpoint par endpoint |

### Modifiés

| Fichier | Modification |
|---|---|
| `pom.xml` | `data-redis`, `cache`, `aspectj` (aucune dépendance pour le retry) |
| `product/Product.java` | ajout de `@Version long version` |
| `product/ProductRepository.java` | `findByIdForUpdate` (`PESSIMISTIC_WRITE` + timeout), `search`, `findTopByStock` |
| `product/ProductService.java` | `@Cacheable`, `@CachePut`, `@CacheEvict`, `@Caching` |
| `product/ProductController.java` | `/cached`, `/by-sku/{sku}` |
| `product/dto/ProductResponse.java` | exposition de `version` |
| `common/exception/GlobalExceptionHandler.java` | 4 nouveaux gestionnaires |
| `application.yml` | Redis + pool, `cache.type: redis`, logs `TRACE` |

**État du build :** `./mvnw compile` → **BUILD SUCCESS**.

---

## 10. Limites assumées

Ces points sont volontairement laissés en l'état ; les connaître fait partie de l'apprentissage.

1. **`AdminCacheController` n'a aucune authentification.** Il liste des clés et vide n'importe quel
   cache. Il ne doit pas sortir de `localhost` sans une contrainte de sécurité.
2. **Aucun test automatisé n'a été écrit.** Un projet réel aurait des tests avec Testcontainers
   (Redis + PostgreSQL) pour vérifier les scénarios de course de façon reproductible.
3. **Le verrou Redis n'est ni réentrant ni supervisé par un watchdog** — voir [§6.4](#64-stratégie-c--le-verrou-distribué-redis).
4. **`sync = true` protège une JVM, pas un cluster** — voir [§4.4](#44-les-patterns-de-cache-implémentés).
5. **Les `sleepQuietly()` sont des artifices pédagogiques**, destinés à rendre les races
   reproductibles. Ils n'ont évidemment rien à faire dans du code réel.
6. **`SupplierGateway` simule une panne** via `failurePercent`. Un vrai client HTTP y ajouterait un
   *circuit breaker* : après N échecs consécutifs, on arrête d'essayer pendant un moment, au lieu de
   réessayer indéfiniment un service que l'on sait mort. C'est la suite logique de ce projet.

---

## Récapitulatif en une page

| Concept | Le vrai problème | La réponse ici | Le piège à éviter |
|---|---|---|---|
| **Cache** | La base ne tient pas la charge en lecture | `@Cacheable` + TTL par cache | Mettre en cache sans TTL, ou sans stratégie d'éviction |
| **TTL** | Combien de temps peut-on servir du périmé ? | 30 s à 10 min selon le métier | Un TTL global unique |
| **Ruée** | Une clé chaude expire, tout le monde recalcule | `sync = true` | Croire que `sync` protège le cluster |
| **Pénétration** | Les clés absentes contournent le cache | Mettre les `null` en cache | Les mettre en cache là où c'est dangereux (`stockLevel`) |
| **Panne cache** | Redis tombe → l'application tombe | `CacheErrorHandler` *fail-open* | Le comportement par défaut : tout échoue |
| **Retry** | Pannes transitoires du réseau | `@Retryable` + `includes` explicites | Réessayer un `POST` non idempotent, ou un `400` |
| **Backoff** | Les retries achèvent le service | exponentiel + `jitter` | Cadence fixe = clients synchronisés |
| **Repli** | Toutes les tentatives ont échoué | `try/catch` manuel, réponse dégradée | Dégrader une opération financière |
| **Race** | lire → décider → écrire n'est pas atomique | 4 stratégies comparées | Croire que `@Transactional` suffit |
| **Verrou distribué** | Plusieurs instances, une section critique | `SET NX PX` + libération Lua | `DEL` au lieu de comparer le jeton |
| **Interblocage** | Deux verrous, deux ordres | trier les clés | Verrouiller dans l'ordre des arguments |
| **Proxies** | L'annotation ne fait rien, en silence | découpage en deux beans | `this.methodeAnnotee()` |
