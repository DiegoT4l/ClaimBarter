package io.github.diegot4l.claimbarter;

/** Thrown when config.yml holds a value the plugin cannot run with. */
final class InvalidSettingException extends Exception
{
    InvalidSettingException(String path, String problem)
    {
        super(path + ": " + problem);
    }
}
