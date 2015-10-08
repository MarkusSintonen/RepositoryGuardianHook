package com.github.markus.stashplugins;

import com.atlassian.stash.hook.HookResponse;
import com.atlassian.stash.hook.repository.PreReceiveRepositoryHook;
import com.atlassian.stash.hook.repository.RepositoryHookContext;
import com.atlassian.stash.hook.repository.RepositoryMergeRequestCheck;
import com.atlassian.stash.hook.repository.RepositoryMergeRequestCheckContext;
import com.atlassian.stash.pull.PullRequestRef;
import com.atlassian.stash.pull.PullRequest;
import com.atlassian.stash.scm.pull.MergeRequest;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.setting.Settings;
import com.atlassian.stash.commit.CommitService;
import com.atlassian.stash.content.*;
import com.atlassian.stash.user.StashAuthenticationContext;
import com.atlassian.stash.user.StashUser;
import com.atlassian.stash.user.UserService;
import com.atlassian.stash.util.Page;
import com.atlassian.stash.util.PageRequest;
import com.atlassian.stash.util.PageRequestImpl;
import com.google.common.base.Function;

import java.util.*;

public class RepositoryGuardianHook implements PreReceiveRepositoryHook, RepositoryMergeRequestCheck
{
	private static final PageRequest PAGE_REQUEST = new PageRequestImpl(0, PageRequest.MAX_PAGE_LIMIT);

	private final CommitService _commitService;
	private final UserService _userService;
	private final StashAuthenticationContext _stashAuthenticationContext;

	public RepositoryGuardianHook(CommitService commitService,
									UserService userService,
									StashAuthenticationContext stashAuthenticationContext) 
	{
		_commitService = commitService;
		_userService = userService;
		_stashAuthenticationContext = stashAuthenticationContext;
	}

    @Override
    public boolean onReceive(RepositoryHookContext context, Collection<RefChange> refChanges, HookResponse hookResponse)
    {
        Repository repository = context.getRepository();
        Settings settings = context.getSettings();
        
		for (RefChange refChange : refChanges) 
		{
			String refId = refChange.getRefId();
			String fromHash = refChange.getFromHash();
			String toHash = refChange.getToHash();
			
			String denyMsg = validateChangesets(repository, settings, refId, fromHash, toHash);
            if (denyMsg == null)
            {
            	return true;
            }
            else
            {
            	hookResponse.err().println("Push rejected!");
            	if (denyMsg.length() > 0)
            	{
	            	String[] msgLines = denyMsg.split("\\r?\\n");
	            	for (String msgLine : msgLines)
	            	{
	            		hookResponse.err().println(msgLine);
	            	}
            	}
            	return false;
            }
        }
		
		return true;
    }
	
	@Override
    public void check(RepositoryMergeRequestCheckContext context) 
	{
		MergeRequest mergeRequest = context.getMergeRequest();
		PullRequest pullRequest = mergeRequest.getPullRequest();
	
		Repository repository = pullRequest.getFromRef().getRepository();
        Settings settings = context.getSettings();
		
		PullRequestRef fromRef = pullRequest.getFromRef();
        PullRequestRef toRef = pullRequest.getToRef();
		
		String refId = toRef.getId();
		String fromHash = toRef.getLatestChangeset();
		String toHash = fromRef.getLatestChangeset();
		
		String denyMsg = validateChangesets(repository, settings, refId, fromHash, toHash);
		if (denyMsg != null) 
		{
            mergeRequest.veto("Push rejected!", denyMsg);
        }
	}
	
	private String validateChangesets(Repository repository, Settings settings, String refId, String fromHash, String toHash)
	{
		ChangesetsBetweenRequest changesetsBetweenRequest = new ChangesetsBetweenRequest.Builder(repository)
                .exclude(fromHash)
                .include(toHash)
                .build();
        Page<Changeset> changesets = _commitService.getChangesetsBetween(changesetsBetweenRequest, PAGE_REQUEST);
		
		Page<String> changeSetIds = changesets.transform(new Function<Changeset, String>() {
                @Override
                public String apply(Changeset changeset) {
                    return changeset.getId();
                }
            });

		DetailedChangesetsRequest detailedChangesetsRequest = new DetailedChangesetsRequest.Builder(repository)
			.changesetIds(changeSetIds.getValues())
			.maxChangesPerCommit(PageRequest.MAX_PAGE_LIMIT)
			.build();
		Page<DetailedChangeset> detailedChangesets =  _commitService.getDetailedChangesets(detailedChangesetsRequest, PAGE_REQUEST);
		
		List<String> paths = new ArrayList<String>();
		
		for (DetailedChangeset detailedChangeset : detailedChangesets.getValues()) 
		{
			for (Change change : detailedChangeset.getChanges().getValues())
			{
				String path = change.getPath().toString();
				paths.add(path);
			}
		}
		
		StashUser user = _stashAuthenticationContext.getCurrentUser();
		List<String> userGroups = getUserGroups(user);
		
		XmlRuleEvaluator evaluator = new XmlRuleEvaluator(settings);
		return evaluator.validatePermissions(paths, refId, user.getName(), userGroups);
	}
	
	private List<String> getUserGroups(StashUser user)
	{
		Page<String> userGroups = _userService.findGroupsByUser(user.getName(), PAGE_REQUEST);
		
		ArrayList<String> groups = new ArrayList<String>();
		
		for (String group : userGroups.getValues())
		{
			groups.add(group);
		}
		
		return groups;
	}
}
