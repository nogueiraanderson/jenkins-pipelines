import boto3
import logging
from typing import List, Optional

def get_regions_list(exclude_regions: Optional[List[str]] = None) -> List[str]:
    """
    Get list of all AWS regions, optionally excluding specific regions.
    
    Args:
        exclude_regions: List of region names to exclude from the result
    
    Returns:
        List of AWS region names
    """
    client = boto3.client('ec2')
    all_regions = [region['RegionName'] for region in client.describe_regions()['Regions']]
    
    if exclude_regions:
        filtered_regions = [r for r in all_regions if r not in exclude_regions
        logging.info(f"Excluded regions: {exclude_regions}")
        return filtered_regions
    
    return all_regions


def get_default_vpc(region: str) -> Optional[str]:
    """
    Get the default VPC ID for a given region.
    
    Args:
        region: AWS region name
    
    Returns:
        Default VPC ID if exists, None otherwise
    """
    ec2 = boto3.resource('ec2', region_name=region)
    
    try:
        vpcs = list(ec2.vpcs.filter(Filters=[{'Name': 'is-default', 'Values': ['true']}]))
        if vpcs:
            return vpcs[0].id
    except Exception as e:
        logging.error(f"Error getting default VPC for region {region}: {e}")
    
    return None
