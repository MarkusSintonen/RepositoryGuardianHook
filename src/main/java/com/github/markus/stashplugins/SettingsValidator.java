package com.github.markus.stashplugins;

import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.setting.RepositorySettingsValidator;
import com.atlassian.stash.setting.Settings;
import com.atlassian.stash.setting.SettingsValidationErrors;

public class SettingsValidator implements RepositorySettingsValidator 
{
    @Override
    public void validate(Settings settings, SettingsValidationErrors settingsValidationErrors, Repository repository) 
	{
    	XmlRuleEvaluator evaluator = new XmlRuleEvaluator(settings);
    	evaluator.validateSettings(settingsValidationErrors);
    }
}
