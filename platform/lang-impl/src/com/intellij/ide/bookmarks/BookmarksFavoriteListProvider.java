// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.bookmarks;

import com.intellij.ide.bookmark.BookmarkType;
import com.intellij.ide.favoritesTreeView.AbstractFavoritesListProvider;
import com.intellij.ide.favoritesTreeView.FavoritesManager;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.CommonActionsPanel;
import com.intellij.ui.IconManager;
import com.intellij.ui.icons.RowIcon;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.EmptyIcon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import javax.swing.*;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class BookmarksFavoriteListProvider extends AbstractFavoritesListProvider<Bookmark> implements BookmarksListener {
  @VisibleForTesting
  public static final Icon BOOKMARK = BookmarkType.DEFAULT.getIcon();

  public BookmarksFavoriteListProvider(Project project) {
    super(project, "Bookmarks");

    project.getMessageBus().connect().subscribe(BookmarksListener.TOPIC, this);
    updateChildren();
  }

  @Override
  public void bookmarkAdded(@NotNull Bookmark b) {
    updateChildren();
  }

  @Override
  public void bookmarkRemoved(@NotNull Bookmark b) {
    updateChildren();
  }

  @Override
  public void bookmarkChanged(@NotNull Bookmark b) {
    updateChildren();
  }

  @Override
  public void bookmarksOrderChanged() {
    updateChildren();
  }

  private void updateChildren() {
    if (myProject.isDisposed()) return;
    myChildren.clear();
    List<Bookmark> bookmarks = BookmarkManager.getInstance(myProject).getValidBookmarks();
    for (Bookmark bookmark : bookmarks) {
      AbstractTreeNode<Bookmark> child = new AbstractTreeNode<>(myProject, bookmark) {
        @NotNull
        @Override
        public Collection<? extends AbstractTreeNode<Bookmark>> getChildren() {
          return Collections.emptyList();
        }

        @Override
        public boolean canNavigate() {
          return bookmark.canNavigate();
        }

        @Override
        public boolean canNavigateToSource() {
          return bookmark.canNavigateToSource();
        }

        @Override
        public void navigate(boolean requestFocus) {
          bookmark.navigate(requestFocus);
        }

        @Override
        protected void update(@NotNull PresentationData presentation) {
          presentation.setPresentableText(bookmark.toString());
          presentation.setIcon(bookmark.getIcon());
        }
      };
      child.setParent(myNode);
      myChildren.add(child);
    }
    FavoritesManager.getInstance(myProject).fireListeners(getListName(myProject));
  }

  @Nullable
  @Override
  public String getCustomName(@NotNull CommonActionsPanel.Buttons type) {
    return switch (type) {
      case EDIT -> BookmarkBundle.message("action.bookmark.edit.description");
      case REMOVE -> BookmarkBundle.message("action.bookmark.delete");
      default -> null;
    };
  }

  @Override
  public boolean willHandle(@NotNull CommonActionsPanel.Buttons type, Project project, @NotNull Set<Object> selectedObjects) {
    return switch (type) {
      case EDIT -> ContainerUtil.getOnlyItem(selectedObjects) instanceof AbstractTreeNode<?> node && node.getValue() instanceof Bookmark;
      case REMOVE ->
        ContainerUtil.and(selectedObjects, toRemove -> toRemove instanceof AbstractTreeNode<?> node && node.getValue() instanceof Bookmark);
      default -> false;
    };
  }

  @Override
  public void handle(@NotNull CommonActionsPanel.Buttons type, Project project, @NotNull Set<Object> selectedObjects, JComponent component) {
    switch (type) {
      case EDIT -> {
        if (selectedObjects.size() != 1) {
          break;
        }
        Object toEdit = selectedObjects.iterator().next();
        if (toEdit instanceof AbstractTreeNode && ((AbstractTreeNode<?>)toEdit).getValue() instanceof Bookmark) {
          Bookmark bookmark = (Bookmark)((AbstractTreeNode<?>)toEdit).getValue();
          if (bookmark == null) {
            break;
          }
          BookmarkManager.getInstance(project).editDescription(bookmark, component);
        }
      }
      case REMOVE -> {
        for (Object toRemove : selectedObjects) {
          @SuppressWarnings("unchecked")
          Bookmark bookmark = ((AbstractTreeNode<Bookmark>)toRemove).getValue();
          BookmarkManager.getInstance(project).removeBookmark(bookmark);
        }
      }
      default -> {}
    }
  }

  @Override
  public int getWeight() {
    return BOOKMARKS_WEIGHT;
  }

  @Override
  public void customizeRenderer(ColoredTreeCellRenderer renderer,
                                JTree tree,
                                @NotNull Object value,
                                boolean selected,
                                boolean expanded,
                                boolean leaf,
                                int row,
                                boolean hasFocus) {
    renderer.clear();
    renderer.setIcon(BOOKMARK);
    if (value instanceof Bookmark) {
      Bookmark bookmark = (Bookmark)value;
      BookmarkItem.setupRenderer(renderer, myProject, bookmark, selected);
      if (renderer.getIcon() != null) {
        RowIcon icon = IconManager.getInstance().createRowIcon(3, RowIcon.Alignment.CENTER);
        icon.setIcon(bookmark.getIcon(), 0);
        icon.setIcon(JBUIScale.scaleIcon(EmptyIcon.create(1)), 1);
        icon.setIcon(renderer.getIcon(), 2);
        renderer.setIcon(icon);
      }
      else {
        renderer.setIcon(bookmark.getIcon());
      }
    }
    else {
      //noinspection HardCodedStringLiteral
      renderer.append(getListName(myProject));
    }
  }
}
