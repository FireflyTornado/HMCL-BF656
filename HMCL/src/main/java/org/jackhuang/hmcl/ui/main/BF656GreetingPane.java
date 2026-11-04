/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.main;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.bf656.BF656Greeting;
import org.jackhuang.hmcl.bf656.BF656GreetingLayout;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ResourceBundle;
import java.util.function.Function;

/// Compact, noninteractive home-sidebar greeting with a stable two-line footprint and no tooltip.
@NotNullByDefault
public final class BF656GreetingPane extends StackPane {
    /// Current selected account's profile name, supplied by the owning page rather than a global subscription.
    private final ObjectProperty<@Nullable String> nickname = new SimpleObjectProperty<>(this, "nickname");
    /// Label styled with the launcher's normal theme colors and font family.
    private final Label label = new Label();
    /// Off-scene ruler using exactly the label's resolved font for width and line-height measurement.
    private final Text ruler = new Text();
    /// Selection function kept separate from rendering so layout changes cannot redraw the greeting.
    private final Function<@Nullable String, BF656Greeting.Greeting> selector;
    /// Localized templates used by the retained greeting.
    private final ResourceBundle messages;
    /// Current message identity, retained while nicknames and available widths change.
    private BF656Greeting.Greeting greeting = new BF656Greeting.Greeting(BF656Greeting.Kind.WELCOME, null);
    /// Optional page-lifecycle coordinator; standalone panes may select immediately for detached rendering.
    private @Nullable Runnable selectionRequest;

    /// Creates the production pane using the device clock, standard probabilities and current launcher language.
    public BF656GreetingPane() {
        this(BF656Greeting::generate, I18n.getResourceBundle());
    }

    /// Allows deterministic rendering checks without changing the device clock or launcher accounts.
    BF656GreetingPane(Function<@Nullable String, BF656Greeting.Greeting> selector, ResourceBundle messages) {
        this.selector = selector;
        this.messages = messages;
        setId("bf656-greeting");
        getStyleClass().add("bf656-greeting");
        setMinWidth(0);
        setPrefWidth(200);
        setMinHeight(Region.USE_PREF_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        label.setId("bf656-greeting-text");
        label.setManaged(false);
        label.setAlignment(Pos.TOP_LEFT);
        label.setTextOverrun(OverrunStyle.CLIP);
        label.setWrapText(false);
        label.setMouseTransparent(true);
        label.fontProperty().addListener(observable -> requestLayout());
        label.lineSpacingProperty().addListener(observable -> requestLayout());
        getChildren().setAll(label);
        nickname.addListener(observable -> updateNickname());
        setAccessibleText(greeting.text(messages).singleLine());
    }

    /// Profile-name binding owned by the home page, including updates to the currently selected account.
    public ObjectProperty<@Nullable String> nicknameProperty() {
        return nickname;
    }

    /// Selects once using the latest nickname and time when the page lifecycle delivers an eligible refresh.
    public void refreshGreeting() {
        greeting = selector.apply(nickname.get());
        setAccessibleText(greeting.text(messages).singleLine());
        requestLayout();
    }

    /// Defers the first personalized selection to the owner's visibility coordinator before binding an account.
    void setSelectionRequest(Runnable selectionRequest) {
        this.selectionRequest = selectionRequest;
    }

    /// Normalizes the displayed name while retaining the raw property and handles transitions to/from the welcome.
    /// Updating an existing personalized greeting retains its selected message identity.
    private void updateNickname() {
        @Nullable String name = BF656Greeting.normalizeNickname(nickname.get());
        if (name == null) {
            greeting = new BF656Greeting.Greeting(BF656Greeting.Kind.WELCOME, null);
        } else if (greeting.kind() == BF656Greeting.Kind.WELCOME) {
            if (selectionRequest != null)
                selectionRequest.run();
            else
                greeting = selector.apply(name);
        } else {
            greeting = new BF656Greeting.Greeting(greeting.kind(), name);
        }
        setAccessibleText(greeting.text(messages).singleLine());
        requestLayout();
    }

    /// Measures a candidate as plain text with the label's current resolved font.
    private double measure(String value) {
        ruler.setFont(label.getFont());
        ruler.setText(value);
        return Math.ceil(ruler.getLayoutBounds().getWidth());
    }

    /// Reserves two font-sized lines plus padding even when the actual greeting fits on one line.
    @Override
    protected double computePrefHeight(double width) {
        ruler.setFont(label.getFont());
        ruler.setLineSpacing(label.getLineSpacing());
        ruler.setText("Ag国\nAg国");
        return snappedTopInset() + Math.ceil(ruler.getLayoutBounds().getHeight()) + snappedBottomInset();
    }

    /// Reflows retained copy after width or font changes, shortening only a nickname after the preferred break.
    @Override
    protected void layoutChildren() {
        double width = Math.max(0, getWidth() - snappedLeftInset() - snappedRightInset());
        var text = greeting.text(messages);
        String visibleText = BF656GreetingLayout.fit(text, width, this::measure);
        label.setText(visibleText);
        label.resizeRelocate(snappedLeftInset(), snappedTopInset(), width,
                Math.max(0, getHeight() - snappedTopInset() - snappedBottomInset()));
    }
}
