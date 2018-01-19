package in.oneton.idea.spring.assistant.plugin.service;

import com.google.gson.Gson;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VirtualFile;
import in.oneton.idea.spring.assistant.plugin.model.ContainerInfo;
import in.oneton.idea.spring.assistant.plugin.model.MetadataNode;
import in.oneton.idea.spring.assistant.plugin.model.Suggestion;
import in.oneton.idea.spring.assistant.plugin.model.json.SpringConfigurationMetadata;
import in.oneton.idea.spring.assistant.plugin.model.json.SpringConfigurationMetadataGroup;
import in.oneton.idea.spring.assistant.plugin.model.json.SpringConfigurationMetadataHint;
import in.oneton.idea.spring.assistant.plugin.model.json.SpringConfigurationMetadataProperty;
import org.apache.commons.collections4.Trie;
import org.apache.commons.collections4.trie.PatriciaTrie;
import org.apache.commons.lang.time.StopWatch;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.Future;

import static com.intellij.openapi.application.ApplicationManager.getApplication;
import static in.oneton.idea.spring.assistant.plugin.Util.PERIOD_DELIMITER;
import static in.oneton.idea.spring.assistant.plugin.model.ContainerInfo.getContainerFile;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public class SuggestionIndexServiceImpl implements SuggestionIndexService {

  private static final Logger log = Logger.getInstance(SuggestionIndexServiceImpl.class);

  // TODO: Need to check if the project level items can be removed
  protected final Map<String, ContainerInfo> projectSeenContainerPathToContainerInfo;
  protected final Trie<String, MetadataNode> projectSanitisedRootSearchIndex;
  protected final Map<String, Map<String, ContainerInfo>>
      moduleNameToSeenContainerPathToContainerInfo;
  private final Map<String, Trie<String, MetadataNode>> moduleNameToSanitisedRootSearchIndex;
  private Future<?> currentExecution;
  private volatile boolean indexingInProgress;

  SuggestionIndexServiceImpl() {
    projectSeenContainerPathToContainerInfo = new HashMap<>();
    projectSanitisedRootSearchIndex = new PatriciaTrie<>();

    moduleNameToSeenContainerPathToContainerInfo = new HashMap<>();
    moduleNameToSanitisedRootSearchIndex = new HashMap<>();
  }

  @Override
  public void init(Project project) {
    reIndex(project);
  }

  @Override
  public void reIndex(Project project) {
    if (indexingInProgress) {
      currentExecution.cancel(false);
    }
    //noinspection CodeBlock2Expr
    currentExecution = getApplication().executeOnPooledThread(() -> {
      getApplication().runReadAction(() -> {
        indexingInProgress = true;
        StopWatch timer = new StopWatch();
        timer.start();
        try {
          debug(() -> log.debug("-> Indexing requested for project " + project.getName()));
          // OrderEnumerator.orderEntries(project) is returning everything from all modules including root level module(which is called project in gradle terms)
          // So, we should not be doing anything with this

          Module[] modules = ModuleManager.getInstance(project).getModules();
          for (Module module : modules) {
            reindexModule(emptyList(), emptyList(), module);
          }
        } finally {
          indexingInProgress = false;
          timer.stop();
          debug(() -> log
              .debug("<- Indexing took " + timer.toString() + " for project " + project.getName()));
        }
      });
    });
  }

  @Override
  public void reindex(Project project, Module[] modules) {
    if (indexingInProgress) {
      if (currentExecution != null) {
        currentExecution.cancel(false);
      }
    }
    //noinspection CodeBlock2Expr
    currentExecution = getApplication().executeOnPooledThread(() -> {
      getApplication().runReadAction(() -> {
        debug(() -> log.debug(
            "-> Indexing requested for a subset of modules of project " + project.getName()));
        indexingInProgress = true;
        StopWatch timer = new StopWatch();
        timer.start();
        try {
          for (Module module : modules) {
            debug(() -> log.debug("--> Indexing requested for module " + module.getName()));
            StopWatch moduleTimer = new StopWatch();
            moduleTimer.start();
            try {
              reindexModule(emptyList(), emptyList(), module);
            } finally {
              moduleTimer.stop();
              debug(() -> log.debug(
                  "<-- Indexing took " + moduleTimer.toString() + " for module " + module
                      .getName()));
            }
          }
        } finally {
          indexingInProgress = false;
          timer.stop();
          debug(() -> log
              .debug("<- Indexing took " + timer.toString() + " for project " + project.getName()));
        }
      });
    });
  }

  @Override
  public void reindex(Project project, Module module) {
    reindex(project, new Module[] {module});
  }

  @Nullable
  @Override
  public MetadataNode findDeepestExactMatch(Project project, List<String> containerElements) {
    String[] pathSegments =
        containerElements.stream().flatMap(element -> stream(toPathSegments(element)))
            .toArray(String[]::new);
    MetadataNode searchStartNode =
        projectSanitisedRootSearchIndex.get(MetadataNode.sanitize(pathSegments[0]));
    if (searchStartNode != null) {
      if (pathSegments.length > 1) {
        return searchStartNode.findDeepestMatch(pathSegments, 1, true);
      }
      return searchStartNode;
    }
    return null;
  }

  @Nullable
  @Override
  public MetadataNode findDeepestExactMatch(Project project, Module module,
      List<String> containerElements) {
    if (moduleNameToSanitisedRootSearchIndex.containsKey(module.getName())) {
      String[] pathSegments =
          containerElements.stream().flatMap(element -> stream(toPathSegments(element)))
              .toArray(String[]::new);
      MetadataNode searchStartNode = moduleNameToSanitisedRootSearchIndex.get(module.getName())
          .get(MetadataNode.sanitize(pathSegments[0]));
      if (searchStartNode != null) {
        if (pathSegments.length > 1) {
          return searchStartNode.findDeepestMatch(pathSegments, 1, true);
        }
        return searchStartNode;
      }
    }
    return null;
  }

  @Override
  public boolean canProvideSuggestions(Project project) {
    return moduleNameToSanitisedRootSearchIndex.values().stream().mapToInt(Map::size).sum() != 0;
  }

  @Override
  public boolean canProvideSuggestions(Project project, Module module) {
    Trie<String, MetadataNode> sanitisedRootSearchIndex =
        moduleNameToSanitisedRootSearchIndex.get(module.getName());
    return sanitisedRootSearchIndex != null && sanitisedRootSearchIndex.size() != 0;
  }

  @Override
  public List<LookupElementBuilder> computeSuggestions(Project project, ClassLoader classLoader,
      @Nullable List<String> ancestralKeys, String queryString) {
    return computeSuggestions(projectSanitisedRootSearchIndex, classLoader, ancestralKeys,
        queryString);
  }

  @Override
  public List<LookupElementBuilder> computeSuggestions(Project project, Module module,
      ClassLoader classLoader, @Nullable List<String> ancestralKeys, String queryString) {
    return computeSuggestions(moduleNameToSanitisedRootSearchIndex.get(module.getName()),
        classLoader, ancestralKeys, queryString);
  }

  private List<ContainerInfo> computeNewContainersToProcess(OrderEnumerator orderEnumerator,
      Map<String, ContainerInfo> seenContainerPathToContainerInfo) {
    List<ContainerInfo> containersToProcess = new ArrayList<>();
    for (VirtualFile metadataFileContainer : orderEnumerator.recursively().classes().getRoots()) {
      ContainerInfo containerInfo = ContainerInfo.newInstance(metadataFileContainer);
      boolean seenBefore =
          seenContainerPathToContainerInfo.containsKey(containerInfo.getContainerPath());

      boolean updatedSinceLastSeen = false;
      if (seenBefore) {
        ContainerInfo seenContainerInfo =
            seenContainerPathToContainerInfo.get(containerInfo.getContainerPath());
        updatedSinceLastSeen = containerInfo.isModified(seenContainerInfo);
        if (updatedSinceLastSeen) {
          debug(() -> log.debug(
              "Container seems to have been updated. Previous version: " + seenContainerInfo
                  + "; Newer version: " + containerInfo));
        }
      }

      boolean looksFresh = !seenBefore || updatedSinceLastSeen;
      boolean processMetadata = looksFresh && containerInfo.containsMetadataFile();
      if (processMetadata) {
        containersToProcess.add(containerInfo);
      }

      if (looksFresh) {
        seenContainerPathToContainerInfo.put(containerInfo.getContainerPath(), containerInfo);
      }
    }

    if (containersToProcess.size() == 0) {
      debug(() -> log.debug("No (new)metadata files to index"));
    }
    return containersToProcess;
  }

  /**
   * Finds the containers that are not reachable from current classpath
   *
   * @param orderEnumerator                  classpath roots to work with
   * @param seenContainerPathToContainerInfo seen container paths
   * @return list of container paths that are no longer valid
   */
  private List<ContainerInfo> computeContainersToRemove(OrderEnumerator orderEnumerator,
      Map<String, ContainerInfo> seenContainerPathToContainerInfo) {
    Set<String> newContainerPaths =
        Arrays.stream(orderEnumerator.recursively().classes().getRoots())
            .map(metadataFileContainer -> getContainerFile(metadataFileContainer).getUrl())
            .collect(toSet());
    Set<String> knownContainerPathSet = new HashSet<>(seenContainerPathToContainerInfo.keySet());
    knownContainerPathSet.removeAll(newContainerPaths);
    return knownContainerPathSet.stream().map(seenContainerPathToContainerInfo::get)
        .collect(toList());
  }

  private void processContainers(List<ContainerInfo> containersToProcess,
      List<ContainerInfo> containersToRemove,
      Map<String, ContainerInfo> seenContainerPathToContainerInfo,
      Trie<String, MetadataNode> sanitisedRootSearchIndex) {
    // Lets remove references to files that are no longer present in classpath
    containersToRemove.forEach(
        container -> removeReferences(seenContainerPathToContainerInfo, sanitisedRootSearchIndex,
            container));

    for (ContainerInfo containerInfo : containersToProcess) {
      // lets remove existing references from search index, as these files are modified, so that we can rebuild index
      if (seenContainerPathToContainerInfo.containsKey(containerInfo.getContainerPath())) {
        removeReferences(seenContainerPathToContainerInfo, sanitisedRootSearchIndex, containerInfo);
      }

      String metadataFilePath = containerInfo.getPath();
      try (InputStream inputStream = containerInfo.getMetadataFile().getInputStream()) {
        SpringConfigurationMetadata springConfigurationMetadata = new Gson()
            .fromJson(new BufferedReader(new InputStreamReader(inputStream)),
                SpringConfigurationMetadata.class);
        buildMetadataHierarchy(sanitisedRootSearchIndex, containerInfo,
            springConfigurationMetadata);

        seenContainerPathToContainerInfo.put(containerInfo.getContainerPath(), containerInfo);
      } catch (IOException e) {
        log.error("Exception encountered while processing metadata file: " + metadataFilePath, e);
        removeReferences(seenContainerPathToContainerInfo, sanitisedRootSearchIndex, containerInfo);
      }
    }
  }

  private void reindexModule(List<ContainerInfo> newProjectSourcesToProcess,
      List<ContainerInfo> projectContainersToRemove, Module module) {
    Map<String, ContainerInfo> moduleSeenContainerPathToSeenContainerInfo =
        moduleNameToSeenContainerPathToContainerInfo
            .computeIfAbsent(module.getName(), k -> new HashMap<>());

    Trie<String, MetadataNode> moduleSanitisedRootSearchIndex =
        moduleNameToSanitisedRootSearchIndex.get(module.getName());
    if (moduleSanitisedRootSearchIndex == null) {
      moduleSanitisedRootSearchIndex = new PatriciaTrie<>();
      moduleNameToSanitisedRootSearchIndex.put(module.getName(), moduleSanitisedRootSearchIndex);
    }

    OrderEnumerator moduleOrderEnumerator = OrderEnumerator.orderEntries(module);

    List<ContainerInfo> newModuleContainersToProcess =
        computeNewContainersToProcess(moduleOrderEnumerator,
            moduleSeenContainerPathToSeenContainerInfo);
    newModuleContainersToProcess.addAll(newProjectSourcesToProcess);

    List<ContainerInfo> moduleContainersToRemove = computeContainersToRemove(moduleOrderEnumerator,
        moduleSeenContainerPathToSeenContainerInfo);
    moduleContainersToRemove.addAll(projectContainersToRemove);

    processContainers(newModuleContainersToProcess, moduleContainersToRemove,
        moduleSeenContainerPathToSeenContainerInfo, moduleSanitisedRootSearchIndex);
  }

  private List<LookupElementBuilder> computeSuggestions(
      Trie<String, MetadataNode> sanitisedRootSearchIndex, ClassLoader classLoader,
      @Nullable List<String> ancestralKeys, String queryString) {
    debug(() -> log.debug("Search requested for " + queryString));
    StopWatch timer = new StopWatch();
    timer.start();
    try {
      String sanitizedQueryString = MetadataNode.sanitize(queryString);
      String[] querySegments = toPathSegments(sanitizedQueryString);
      Set<Suggestion> suggestions = null;
      if (ancestralKeys != null) {
        String[] pathSegments =
            ancestralKeys.stream().flatMap(element -> stream(toPathSegments(element)))
                .toArray(String[]::new);
        MetadataNode searchStartNode =
            sanitisedRootSearchIndex.get(MetadataNode.sanitize(pathSegments[0]));
        if (searchStartNode != null) {
          if (pathSegments.length > 1) {
            searchStartNode = searchStartNode.findDeepestMatch(pathSegments, 1, true);
          }
          if (searchStartNode != null) {
            if (!searchStartNode.isLeaf()) {
              suggestions =
                  searchStartNode.findChildSuggestions(querySegments, 0, 0, classLoader, false);

              // since we don't have any matches at the root level, may be a subset of intermediary nodes might match the entered string
              if (suggestions == null) {
                suggestions =
                    searchStartNode.findChildSuggestions(querySegments, 0, 0, classLoader, true);
              }
            } else {
              // if the start node is a leaf, this means, the user is looking for values for the given key, lets find the suggestions for values
              suggestions = searchStartNode.getSuggestionValues(classLoader);
            }
          }
        }
      } else {
        String sanitisedQuerySegment = MetadataNode.sanitize(querySegments[0]);
        SortedMap<String, MetadataNode> topLevelQueryResults =
            sanitisedRootSearchIndex.prefixMap(sanitisedQuerySegment);
        Collection<MetadataNode> childNodes = topLevelQueryResults.values();
        suggestions = getSuggestions(classLoader, querySegments, childNodes, 1, 1, false);

        // since we don't have any matches at the root level, may be a subset of intermediary nodes might match the entered string
        if (suggestions == null) {
          Collection<MetadataNode> nodesToSearchWithin = sanitisedRootSearchIndex.values();
          suggestions = getSuggestions(classLoader, querySegments, nodesToSearchWithin, 1, 0, true);
        }
      }

      if (suggestions != null) {
        return toLookupElementBuilders(suggestions, classLoader);
      }
      return null;
    } finally {
      timer.stop();
      debug(() -> log.debug("Search took " + timer.toString()));
    }
  }

  @Nullable
  private Set<Suggestion> getSuggestions(ClassLoader classLoader, String[] querySegments,
      Collection<MetadataNode> nodesToSearchWithin,
      @SuppressWarnings("SameParameterValue") int suggestionDepth, int startWith,
      boolean proceedTillLeaf) {
    Set<Suggestion> suggestions = null;
    for (MetadataNode metadataNode : nodesToSearchWithin) {
      Set<Suggestion> matchedSuggestions = metadataNode
          .findSuggestions(querySegments, suggestionDepth, startWith, classLoader, proceedTillLeaf);
      if (matchedSuggestions != null) {
        if (suggestions == null) {
          suggestions = new HashSet<>();
        }
        suggestions.addAll(matchedSuggestions);
      }
    }
    return suggestions;
  }

  private void buildMetadataHierarchy(Trie<String, MetadataNode> sanitisedRootSearchIndex,
      ContainerInfo containerInfo, SpringConfigurationMetadata springConfigurationMetadata) {
    debug(() -> log.debug("Adding container to index " + containerInfo));
    String containerPath = containerInfo.getContainerPath();
    // populate groups
    List<SpringConfigurationMetadataGroup> groups = springConfigurationMetadata.getGroups();
    if (groups != null) {
      groups.sort(comparing(SpringConfigurationMetadataGroup::getName));
      for (SpringConfigurationMetadataGroup group : groups) {
        String[] pathSegments = toPathSegments(group.getName());
        MetadataNode closestMetadata =
            findDeepestMatch(sanitisedRootSearchIndex, pathSegments, false);
        if (closestMetadata == null) {
          String firstSegment = pathSegments[0];
          closestMetadata = MetadataNode.newInstance(firstSegment, null, containerPath);
          boolean noMoreSegmentsLeft = pathSegments.length == 1;
          if (noMoreSegmentsLeft) {
            closestMetadata.setGroup(group);
          }
          String sanitizedFirstSegment = MetadataNode.sanitize(firstSegment);
          sanitisedRootSearchIndex.put(sanitizedFirstSegment, closestMetadata);
        }
        closestMetadata.addChildren(group, pathSegments, containerPath);
      }
    }

    // populate properties
    List<SpringConfigurationMetadataProperty> properties =
        springConfigurationMetadata.getProperties();
    properties.sort(comparing(SpringConfigurationMetadataProperty::getName));
    for (SpringConfigurationMetadataProperty property : properties) {
      String[] pathSegments = toPathSegments(property.getName());
      MetadataNode closestMetadata =
          findDeepestMatch(sanitisedRootSearchIndex, pathSegments, false);
      if (closestMetadata == null) {
        String firstSegment = pathSegments[0];
        closestMetadata = MetadataNode.newInstance(firstSegment, null, containerPath);
        boolean noMoreSegmentsLeft = pathSegments.length == 1;
        if (noMoreSegmentsLeft) {
          closestMetadata.setProperty(property);
        }
        String sanitizedFirstSegment = MetadataNode.sanitize(firstSegment);
        sanitisedRootSearchIndex.put(sanitizedFirstSegment, closestMetadata);
      }
      closestMetadata.addChildren(property, pathSegments, containerPath);
    }

    // update hints
    List<SpringConfigurationMetadataHint> hints = springConfigurationMetadata.getHints();
    if (hints != null) {
      hints.sort(comparing(SpringConfigurationMetadataHint::getName));
      for (SpringConfigurationMetadataHint hint : hints) {
        String[] pathSegments = toPathSegments(hint.getName());
        MetadataNode closestMetadata =
            findDeepestMatch(sanitisedRootSearchIndex, pathSegments, true);
        if (closestMetadata != null && closestMetadata.getDepth() == pathSegments.length) {
          assert closestMetadata.getProperty() != null;
          closestMetadata.getProperty().setHint(hint);
        }
      }
    }
  }

  @Nullable
  private MetadataNode findDeepestMatch(Map<String, MetadataNode> sanitisedRoots,
      String[] pathSegments, boolean matchAllSegments) {
    String firstSegment = pathSegments[0];
    MetadataNode closestMatchedRoot = sanitisedRoots.get(MetadataNode.sanitize(firstSegment));
    if (closestMatchedRoot != null) {
      closestMatchedRoot = closestMatchedRoot.findDeepestMatch(pathSegments, 1, matchAllSegments);
    }
    return closestMatchedRoot;
  }

  private void removeReferences(Map<String, ContainerInfo> containerPathToContainerInfo,
      Trie<String, MetadataNode> sanitisedRootSearchIndex, ContainerInfo containerInfo) {
    debug(() -> log.debug("Removing references to " + containerInfo));
    String containerPath = containerInfo.getContainerPath();
    containerPathToContainerInfo.remove(containerPath);

    Iterator<String> searchIndexIterator = sanitisedRootSearchIndex.keySet().iterator();
    while (searchIndexIterator.hasNext()) {
      MetadataNode root = sanitisedRootSearchIndex.get(searchIndexIterator.next());
      boolean removeTree = root.removeRef(containerInfo.getContainerPath());
      if (removeTree) {
        searchIndexIterator.remove();
      }
    }
  }

  @Nullable
  private List<LookupElementBuilder> toLookupElementBuilders(@Nullable Set<Suggestion> suggestions,
      ClassLoader classLoader) {
    if (suggestions != null) {
      return suggestions.stream().map(v -> v.newLookupElement(classLoader)).collect(toList());
    }
    return null;
  }

  private String[] toPathSegments(String element) {
    return element.split(PERIOD_DELIMITER, -1);
  }

  /**
   * Debug logging can be enabled by adding fully classified class name/package name with # prefix
   * For eg., to enable debug logging, go `Help > Debug log settings` & type `#in.oneton.idea.spring.assistant.plugin.service.SuggestionIndexServiceImpl`
   *
   * @param doWhenDebug code to execute when debug is enabled
   */
  private void debug(Runnable doWhenDebug) {
    if (log.isDebugEnabled()) {
      doWhenDebug.run();
    }
  }

}
