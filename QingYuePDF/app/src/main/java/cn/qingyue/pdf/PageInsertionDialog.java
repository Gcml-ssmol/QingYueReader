package cn.qingyue.pdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;

/** The current page is explicit; page-after is selected first, including mid-document. */
final class PageInsertionDialog {
    interface Insert { void at(int index); }
    private PageInsertionDialog() {}
    static Dialog show(Activity owner,int page,int pageCount,Insert insertion){
        final int[] choice={0};
        String[] options={PageInsertionPosition.label(page,pageCount,false),PageInsertionPosition.label(page,pageCount,true)};
        AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("插入空白页 · 当前第 "+(page+1)+" 页")
                .setSingleChoiceItems(options,0,(selected,which)->choice[0]=which)
                .setNegativeButton("取消",null).setPositiveButton("插入",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            int index=PageInsertionPosition.index(page,pageCount,choice[0]==1);dialog.dismiss();insertion.at(index);
        }));dialog.show();return dialog;
    }
}
