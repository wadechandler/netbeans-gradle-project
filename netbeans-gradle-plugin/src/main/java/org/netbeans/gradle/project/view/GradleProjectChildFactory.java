package org.netbeans.gradle.project.view;

import java.awt.Image;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.NbIcons;
import org.netbeans.gradle.project.NbStrings;
import org.netbeans.gradle.project.api.event.NbListenerRef;
import org.netbeans.gradle.project.api.event.NbListenerRefs;
import org.netbeans.gradle.project.api.nodes.GradleProjectExtensionNodes;
import org.netbeans.gradle.project.api.nodes.SingleNodeFactory;
import org.netbeans.gradle.project.model.ModelRefreshListener;
import org.netbeans.gradle.project.model.NbGradleModel;
import org.netbeans.gradle.project.model.NbGradleProjectTree;
import org.openide.loaders.DataFolder;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.nodes.ChildFactory;
import org.openide.nodes.Children;
import org.openide.nodes.FilterNode;
import org.openide.nodes.Node;
import org.openide.util.lookup.Lookups;

public final class GradleProjectChildFactory
extends
        ChildFactory.Detachable<SingleNodeFactory> {

    private final NbGradleProject project;
    private final GradleProjectLogicalViewProvider parent;
    private final AtomicReference<NodeExtensions> nodeExtensionsRef;
    private final AtomicBoolean lastHasSubprojects;
    private final List<NbListenerRef> listenerRegistrations;
    private volatile boolean enableRefresh;
    private final AtomicBoolean hasPreventedRefresh;

    public GradleProjectChildFactory(NbGradleProject project, GradleProjectLogicalViewProvider parent) {
        if (project == null) throw new NullPointerException("project");
        if (parent == null) throw new NullPointerException("parent");

        this.project = project;
        this.parent = parent;
        this.nodeExtensionsRef = new AtomicReference<NodeExtensions>(NodeExtensions.EMPTY);
        this.lastHasSubprojects = new AtomicBoolean(false);
        this.listenerRegistrations = new LinkedList<NbListenerRef>();

        this.enableRefresh = true;
        this.hasPreventedRefresh = new AtomicBoolean(false);
    }

    private NbGradleModel getShownModule() {
        return project.getCurrentModel();
    }

    private List<GradleProjectExtensionNodes> getExtensionNodes() {
        List<GradleProjectExtensionNodes> result = new ArrayList<GradleProjectExtensionNodes>(
                project.getCombinedExtensionLookup().lookupAll(GradleProjectExtensionNodes.class));
        return result;
    }

    private void refreshProjectNode() {
        if (enableRefresh) {
            refresh(false);
        }
        else {
            hasPreventedRefresh.set(true);
        }
    }

    private boolean hasSubProjects() {
        return !getShownModule().getMainProject().getChildren().isEmpty();
    }

    private NbListenerRef registerModelRefreshListener() {
        return parent.addChildModelRefreshListener(new ModelRefreshListener() {
            @Override
            public void startRefresh() {
                enableRefresh = false;
            }

            @Override
            public void endRefresh(boolean extensionsChanged) {
                enableRefresh = true;

                boolean needRefresh = hasPreventedRefresh.getAndSet(false);
                if (extensionsChanged || needRefresh) {
                    refreshProjectNode();
                }
            }
        });
    }

    @Override
    protected void addNotify() {
        listenerRegistrations.add(registerModelRefreshListener());

        lastHasSubprojects.set(hasSubProjects());
        final Runnable simpleChangeListener = new Runnable() {
            @Override
            public void run() {
                refreshProjectNode();
            }
        };

        List<GradleProjectExtensionNodes> extensionNodes = getExtensionNodes();

        for (GradleProjectExtensionNodes singleExtensionNodes: extensionNodes) {
            listenerRegistrations.add(singleExtensionNodes.addNodeChangeListener(simpleChangeListener));
        }

        final ChangeListener changeListener = new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                NodeExtensions newNodeExtensions
                        = NodeExtensions.create(getExtensionNodes(), simpleChangeListener);

                // FIXME: This is not trully thread safe because model change
                //   listener might be called after removeNotify, leaving an
                //   unclosed NodeExtensions instance.
                NodeExtensions prevNodeExtensions = nodeExtensionsRef.getAndSet(newNodeExtensions);
                prevNodeExtensions.close();

                boolean newHasSubProjects = hasSubProjects();
                boolean prevHasSubProjects = lastHasSubprojects.getAndSet(newHasSubProjects);
                if (newHasSubProjects != prevHasSubProjects) {
                    refreshProjectNode();
                }

                // FIXME: Commenting the following line breaks NBAndroid.
                //        Detect here that an extension relies on the fact the previous
                //        versions of this plugin did refresh on project reload.
                //        (use newNodeExtensions)
                //simpleChangeListener.run();
            }
        };
        project.addModelChangeListener(changeListener);

        listenerRegistrations.add(0, NbListenerRefs.fromRunnable(new Runnable() {
            @Override
            public void run() {
                project.removeModelChangeListener(changeListener);

                nodeExtensionsRef.getAndSet(NodeExtensions.EMPTY).close();
            }
        }));
    }

    @Override
    protected void removeNotify() {
        Collection<NbListenerRef> toUnregister = new ArrayList<NbListenerRef>(listenerRegistrations);
        listenerRegistrations.clear();

        for (NbListenerRef listenerRef: toUnregister) {
            listenerRef.unregister();
        }
    }

    @Override
    protected Node createNodeForKey(SingleNodeFactory key) {
        return key.createNode();
    }

    private void addProjectFiles(List<SingleNodeFactory> toPopulate) throws DataObjectNotFoundException {
        toPopulate.add(new SingleNodeFactory() {
            @Override
            public Node createNode() {
                return new BuildScriptsNode(project);
            }
        });
    }

    private Node createSimpleNode() {
        DataFolder projectFolder = DataFolder.findFolder(project.getProjectDirectory());
        return projectFolder.getNodeDelegate().cloneNode();
    }

    private Children createSubprojectsChild() {
        return Children.create(new SubProjectsChildFactory(project), true);
    }

    private static Action createOpenAction(String caption,
            Collection<? extends NbGradleProjectTree> modules) {
        return OpenProjectsAction.createFromModules(caption, modules);
    }

    private static void getAllChildren(NbGradleProjectTree module, List<NbGradleProjectTree> result) {
        Collection<NbGradleProjectTree> children = module.getChildren();
        result.addAll(children);
        for (NbGradleProjectTree child: children) {
            getAllChildren(child, result);
        }
    }

    public static List<NbGradleProjectTree> getAllChildren(NbGradleProjectTree module) {
        List<NbGradleProjectTree> result = new LinkedList<NbGradleProjectTree>();
        getAllChildren(module, result);
        return result;
    }

    private static List<NbGradleProjectTree> getAllChildren(NbGradleModel model) {
        List<NbGradleProjectTree> result = new LinkedList<NbGradleProjectTree>();
        getAllChildren(model.getMainProject(), result);
        return result;
    }

    private void addChildren(List<SingleNodeFactory> toPopulate) {
        final NbGradleModel shownModule = getShownModule();
        final Collection<NbGradleProjectTree> immediateChildren
                = shownModule.getMainProject().getChildren();

        if (immediateChildren.isEmpty()) {
            return;
        }
        final List<NbGradleProjectTree> children = getAllChildren(shownModule);

        toPopulate.add(new SingleNodeFactory() {
            @Override
            public Node createNode() {
                return new FilterNode(
                        createSimpleNode(),
                        createSubprojectsChild(),
                        Lookups.singleton(shownModule.getMainProject())) {
                    @Override
                    public String getName() {
                        return "SubProjectsNode_" + getShownModule().getMainProject().getProjectFullName().replace(':', '_');
                    }

                    @Override
                    public Action[] getActions(boolean context) {
                        return new Action[] {
                            createOpenAction(NbStrings.getOpenImmediateSubProjectsCaption(), immediateChildren),
                            createOpenAction(NbStrings.getOpenSubProjectsCaption(), children)
                        };
                    }

                    @Override
                    public String getDisplayName() {
                        return NbStrings.getSubProjectsCaption();
                    }

                    @Override
                    public Image getIcon(int type) {
                        return NbIcons.getGradleIcon();
                    }

                    @Override
                    public Image getOpenedIcon(int type) {
                        return getIcon(type);
                    }

                    @Override
                    public boolean canRename() {
                        return false;
                    }
                };
            }
        });
    }

    private void readKeys(List<SingleNodeFactory> toPopulate) throws DataObjectNotFoundException {
        List<GradleProjectExtensionNodes> extensionNodes = getExtensionNodes();
        if (extensionNodes != null) {
            for (GradleProjectExtensionNodes nodes: extensionNodes) {
                toPopulate.addAll(nodes.getNodeFactories());
            }
        }

        addChildren(toPopulate);
        addProjectFiles(toPopulate);
    }

    @Override
    protected boolean createKeys(List<SingleNodeFactory> toPopulate) {
        try {
            readKeys(toPopulate);
        } catch (DataObjectNotFoundException ex) {
            throw new RuntimeException(ex);
        }
        return true;
    }

    private static final class NodeExtensions {
        private static final NodeExtensions EMPTY = createEmpty();

        private final List<GradleProjectExtensionNodes> nodeFactories;
        private final List<NbListenerRef> listenerRefs;

        private NodeExtensions(
                Collection<? extends GradleProjectExtensionNodes> nodeFactories,
                List<NbListenerRef> listenerRefs) {
            this.nodeFactories = Collections.unmodifiableList(
                    new ArrayList<GradleProjectExtensionNodes>(nodeFactories));

            this.listenerRefs = listenerRefs;
        }

        public static NodeExtensions createEmpty() {
            return create(Collections.<GradleProjectExtensionNodes>emptyList(), new Runnable() {
                @Override
                public void run() {
                    // Do nothing.
                }
            });
        }

        public static NodeExtensions create(
                Collection<? extends GradleProjectExtensionNodes> nodeFactories,
                Runnable changeListener) {

            List<NbListenerRef> listenerRefs = new ArrayList<NbListenerRef>(nodeFactories.size());
            for (GradleProjectExtensionNodes nodeFactory: nodeFactories) {
                listenerRefs.add(nodeFactory.addNodeChangeListener(changeListener));
            }

            return new NodeExtensions(nodeFactories, listenerRefs);
        }

        public List<GradleProjectExtensionNodes> getFactories() {
            return nodeFactories;
        }

        public void close() {
            for (NbListenerRef ref: listenerRefs) {
                ref.unregister();
            }
        }
    }
}
