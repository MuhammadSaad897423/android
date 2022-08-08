/*
 * ownCloud Android client application
 *
 * @author LukeOwncloud
 * @author David A. Velasco
 * @author masensio
 * @author Christian Schabesberger
 * @author Juan Carlos Garrote Gascón
 *
 * Copyright (C) 2022 ownCloud GmbH.
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.activity;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.FragmentTransaction;
import com.owncloud.android.R;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.OCUpload;
import com.owncloud.android.datamodel.UploadsStorageManager;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.operations.CheckCurrentCredentialsOperation;
import com.owncloud.android.presentation.ui.transfers.TransferListFragment;
import com.owncloud.android.ui.fragment.UploadListFragment;
import com.owncloud.android.usecases.transfers.uploads.RetryFailedUploadsForAccountUseCase;
import com.owncloud.android.utils.MimetypeIconUtil;
import kotlin.Lazy;
import org.jetbrains.annotations.NotNull;
import timber.log.Timber;

import java.io.File;

import static org.koin.java.KoinJavaComponent.inject;

/**
 * Activity listing pending, active, and completed uploads. User can delete
 * completed uploads from view. Content of this list of coming from
 * {@link UploadsStorageManager}.
 */
public class UploadListActivity extends FileActivity implements UploadListFragment.ContainerActivity {

    private static final String TAG_UPLOAD_LIST_FRAGMENT = "UPLOAD_LIST_FRAGMENT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        View rightFragmentContainer = findViewById(R.id.right_fragment_container);
        rightFragmentContainer.setVisibility(View.GONE);

        // this activity has no file really bound, it's for multiple accounts at the same time; should no inherit
        // from FileActivity; moreover, some behaviours inherited from FileActivity should be delegated to Fragments;
        // but that's other story
        setFile(null);

        // setup toolbar
        setupRootToolbar(getString(R.string.uploads_view_title), false);

        // setup drawer
        setupDrawer();

        // setup navigation bottom bar
        setupNavigationBottomBar(R.id.nav_uploads);

        // Add fragment with a transaction for setting a tag
        if (savedInstanceState == null) {
            createUploadListFragment();
        } // else, the Fragment Manager makes the job on configuration changes
    }

    private void createUploadListFragment() {
        //UploadListFragment uploadList = new UploadListFragment();
        TransferListFragment uploadList = new TransferListFragment();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.add(R.id.left_fragment_container, uploadList, TAG_UPLOAD_LIST_FRAGMENT);
        transaction.commit();
    }

    // ////////////////////////////////////////
    // UploadListFragment.ContainerActivity
    // ////////////////////////////////////////
    @Override
    public boolean onUploadItemClick(OCUpload file) {
        /// TODO is this path still active?
        File f = new File(file.getLocalPath());
        if (!f.exists()) {
            showSnackMessage(
                    getString(R.string.local_file_not_found_toast)
            );

        } else {
            openFileWithDefault(file.getLocalPath());
        }
        return true;
    }

    /**
     * Open file with app associates with its MIME type. If MIME type unknown, show list with all apps.
     */
    private void openFileWithDefault(String localPath) {
        Intent myIntent = new Intent(android.content.Intent.ACTION_VIEW);
        File file = new File(localPath);
        String mimetype = MimetypeIconUtil.getBestMimeTypeByFilename(localPath);
        if ("application/octet-stream".equals(mimetype)) {
            mimetype = "*/*";
        }
        myIntent.setDataAndType(Uri.fromFile(file), mimetype);
        try {
            startActivity(myIntent);
        } catch (ActivityNotFoundException e) {
            showSnackMessage(
                    getString(R.string.file_list_no_app_for_file_type)
            );
            Timber.i("Could not find app for sending log history.");

        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FileActivity.REQUEST_CODE__UPDATE_CREDENTIALS && resultCode == RESULT_OK) {
            // Retry uploads of the updated account
            Account account = AccountUtils.getOwnCloudAccountByName(
                    this,
                    data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            );
            if (account == null) {
                return;
            }
            @NotNull Lazy<RetryFailedUploadsForAccountUseCase> retryFailedUploadsForAccountUseCaseLazy = inject(RetryFailedUploadsForAccountUseCase.class);
            RetryFailedUploadsForAccountUseCase retryFailedUploadsForAccountUseCase = retryFailedUploadsForAccountUseCaseLazy.getValue();
            retryFailedUploadsForAccountUseCase.execute(new RetryFailedUploadsForAccountUseCase.Params(account.name));
        }
    }

    /**
     * @param operation Operation performed.
     * @param result    Result of the removal.
     */
    @Override
    public void onRemoteOperationFinish(RemoteOperation operation, RemoteOperationResult result) {
        if (operation instanceof CheckCurrentCredentialsOperation) {
            // Do not call super in this case; more refactoring needed around onRemoteOperationFinish :'(
            getFileOperationsHelper().setOpIdWaitingFor(Long.MAX_VALUE);
            dismissLoadingDialog();
            Account account = ((RemoteOperationResult<Account>) result).getData();
            if (!result.isSuccess()) {

                requestCredentialsUpdate();

            } else {
                // already updated -> just retry!
                @NotNull Lazy<RetryFailedUploadsForAccountUseCase> retryFailedUploadsForAccountUseCaseLazy = inject(RetryFailedUploadsForAccountUseCase.class);
                RetryFailedUploadsForAccountUseCase retryFailedUploadsForAccountUseCase = retryFailedUploadsForAccountUseCaseLazy.getValue();
                retryFailedUploadsForAccountUseCase.execute(new RetryFailedUploadsForAccountUseCase.Params(account.name));
            }

        } else {
            super.onRemoteOperationFinish(operation, result);
        }
    }

    /**
     * Called when the ownCloud {@link Account} associated to the Activity was just updated.
     */
    @Override
    protected void onAccountSet(boolean stateWasRecovered) {
        super.onAccountSet(stateWasRecovered);
        if (mAccountWasSet) {
            setAccountInDrawer(getAccount());
        }
    }

}
