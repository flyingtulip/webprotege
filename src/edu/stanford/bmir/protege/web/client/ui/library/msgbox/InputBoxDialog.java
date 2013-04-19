package edu.stanford.bmir.protege.web.client.ui.library.msgbox;

import com.google.gwt.user.client.ui.Focusable;
import com.google.gwt.user.client.ui.Widget;
import edu.stanford.bmir.protege.web.client.ui.library.dlg.*;

/**
 * Author: Matthew Horridge<br>
 * Stanford University<br>
 * Bio-Medical Informatics Research Group<br>
 * Date: 18/04/2013
 */
public class InputBoxDialog extends WebProtegeDialog<String> {

    private InputBoxHandler inputBoxHandler = new InputBoxHandler() {
        @Override
        public void handleAcceptInput(String input) {

        }

    };

    public static void showDialog(String title, InputBoxHandler handler) {
        InputBoxDialog dlg = new InputBoxDialog(title);
        dlg.setInputBoxHandler(handler);
        dlg.setVisible(true);
    }

    public InputBoxDialog(String title) {
        super(new InputBoxController(title));
        setDialogButtonHandler(DialogButton.OK, new WebProtegeDialogButtonHandler<String>() {
            @Override
            public void handleHide(String data, WebProtegeDialogCloser closer) {
                inputBoxHandler.handleAcceptInput(data);
                closer.hide();
            }
        });
    }

    public void setInputBoxHandler(InputBoxHandler inputBoxHandler) {
        this.inputBoxHandler = inputBoxHandler;
    }



    private static class InputBoxController extends WebProtegeOKCancelDialogController<String> {

        private InputBoxView view = new InputBoxViewImpl();

        private InputBoxController(String title) {
            super(title);
        }

        @Override
        public Widget getWidget() {
            return view.getWidget();
        }

        @Override
        public Focusable getInitialFocusable() {
            return view.getInitialFocusable();
        }

        @Override
        public String getData() {
            return view.getInputValue();
        }
    }
}
